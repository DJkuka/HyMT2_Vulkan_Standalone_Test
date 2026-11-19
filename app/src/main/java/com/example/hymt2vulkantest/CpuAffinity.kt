package com.example.hymt2vulkantest

object CpuAffinity {
    init { System.loadLibrary("cpuaffinity") }
    external fun topologyJson(): String
    external fun setAffinityForAllThreads(mask: Long): String
}
