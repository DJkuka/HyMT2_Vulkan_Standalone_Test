#include <jni.h>
#include <sched.h>
#include <dirent.h>
#include <unistd.h>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <algorithm>

static long read_num(const std::string& p) {
    std::ifstream f(p); long v=0; if(f.good()) f>>v; return v;
}
static bool cpu_online(int id) {
    std::string p="/sys/devices/system/cpu/cpu"+std::to_string(id)+"/online";
    std::ifstream f(p); if(!f.good()) return id==0;
    int v=0; f>>v; return v==1;
}
static uint64_t mask_of(const cpu_set_t& s) {
    uint64_t m=0; for(int i=0;i<64;i++) if(CPU_ISSET(i,&s)) m|=(1ULL<<i); return m;
}
static std::string hexmask(uint64_t m) {
    char b[32]; std::snprintf(b,sizeof(b),"0x%llx",(unsigned long long)m); return b;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_hymt2vulkantest_CpuAffinity_topologyJson(JNIEnv* env,jobject) {
    long n=std::min<long>(sysconf(_SC_NPROCESSORS_CONF),64), maxFreq=0, maxCap=0;
    struct Cpu { int id; bool on; long freq; long cap; };
    std::vector<Cpu> cpus;
    for(int i=0;i<n;i++){
        std::string base="/sys/devices/system/cpu/cpu"+std::to_string(i)+"/";
        long f=read_num(base+"cpufreq/cpuinfo_max_freq");
        if(!f) f=read_num(base+"cpufreq/scaling_max_freq");
        long c=read_num(base+"cpu_capacity"); bool on=cpu_online(i);
        cpus.push_back({i,on,f,c});
        if(on){maxFreq=std::max(maxFreq,f);maxCap=std::max(maxCap,c);}
    }
    std::ostringstream out; out<<"{\"cpus\":[";
    for(size_t i=0;i<cpus.size();i++){
        const auto& c=cpus[i]; if(i)out<<",";
        bool perf=c.on && (maxCap>0 ? c.cap==maxCap : (maxFreq>0 && c.freq==maxFreq));
        out<<"{\"id\":"<<c.id<<",\"online\":"<<(c.on?"true":"false")
           <<",\"maxFreqKhz\":"<<c.freq<<",\"capacity\":"<<c.cap
           <<",\"performance\":"<<(perf?"true":"false")<<"}";
    }
    out<<"]}"; return env->NewStringUTF(out.str().c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_hymt2vulkantest_CpuAffinity_setAffinityForAllThreads(JNIEnv* env,jobject,jlong requested) {
    uint64_t mask=(uint64_t)requested;
    cpu_set_t target; CPU_ZERO(&target);
    if(mask==0) {
        // Restore the system-allowed CPU set from the untouched main/UI thread.
        if(sched_getaffinity(getpid(),sizeof(target),&target)!=0)
            return env->NewStringUTF("{\"error\":\"sched_getaffinity(main) failed\"}");
    } else {
        for(int i=0;i<64;i++) if(mask&(1ULL<<i)) CPU_SET(i,&target);
    }
    DIR* dir=opendir("/proc/self/task");
    if(!dir)return env->NewStringUTF("{\"error\":\"cannot open /proc/self/task\"}");
    std::ostringstream out;
    out<<"{\"requested\":\""<<(mask?hexmask(mask):"system-allowed")<<"\",\"threads\":[";
    bool first=true; dirent* ent;
    while((ent=readdir(dir))!=nullptr){
        char* end=nullptr; long value=strtol(ent->d_name,&end,10);
        if(!end||*end!='\0'||value<=0||value==getpid())continue;
        pid_t tid=(pid_t)value; errno=0;
        int rc=sched_setaffinity(tid,sizeof(target),&target), err=rc?errno:0;
        cpu_set_t actual; CPU_ZERO(&actual);
        int gr=sched_getaffinity(tid,sizeof(actual),&actual);
        if(!first)out<<","; first=false;
        out<<"{\"tid\":"<<tid<<",\"rc\":"<<rc<<",\"errno\":"<<err
           <<",\"effective\":\""<<hexmask(gr==0?mask_of(actual):0)<<"\"}";
    }
    closedir(dir); out<<"]}";
    return env->NewStringUTF(out.str().c_str());
}