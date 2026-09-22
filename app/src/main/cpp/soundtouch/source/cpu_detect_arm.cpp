#include "cpu_detect.h"

#ifndef SUPPORT_NEON
#define SUPPORT_NEON 0x0002
#endif

// Must match declaration in cpu_detect.h exactly
// SoundTouch expects this as a free function, not in namespace
uint detectCPUextensions(void)
{
    uint ret = 0;
#if defined(__ARM_NEON__)
    ret |= SUPPORT_NEON;
#endif
    return ret;
}

void disableExtensions(uint wDisableMask)
{
    (void)wDisableMask;
}
