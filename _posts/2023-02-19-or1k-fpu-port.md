---
title: OpenRISC FPU Port - What is It?
layout: post
date: 2023-03-19 06:06
categories: [ hardware, embedded, openrisc ]
---

Last year (2022) the big milestone for OpenRISC was getting the [glibc port upstream](https://openrisc.io/toolchain/2022/02/19/glibc-upstream).
Though there is [libc](https://en.wikipedia.org/wiki/C_standard_library) support for
OpenRISC already with [musl](https://www.musl-libc.org) and [ucLibc](https://uclibc-ng.org)
the glibc port provides a extensive testsuite which has proved useful in shaking out toolchain
and OS bugs.

The upstreamed OpenRISC glibc support is missing support for leveraging the
OpenRISC [floating-point unit (FPU)](https://en.wikipedia.org/wiki/Floating-point_unit).
Adding OpenRISC glibc FPU support requires a cross cutting effort across the
architecture's fullstack from:

 - Architecture Specification
 - Simulators and CPU implementations
 - Linux Kernel support
 - GCC Instructions and Soft FPU
 - Binutils/GDB Debugging Support
 - glibc support

In this blog entry I will cover how the OpenRISC architecture specification
was updated to support user space floating point applications.  But first, what
is FPU porting?

## What is FPU Porting?

The FPU in modern CPU's allows CPU's to do floating point math like addition,
subtraction, multiplication.  When used in a user application the FPU's
function becomes more of a math accelerator, accelerating math operations like
`sin`, `sinf`, `expf`.  Not all FPU's provide the same set
of FPU operations nor do they have to.  When enabled, the compiler will
will add floating point instructions where they can be used.

OpenRISC FPU support was added to GCC [a while back](https://www.phoronix.com/news/GCC-10-OpenRISC-FPU).
We can see how this works with a simple example.

C code example `addf.c`:

```
float addf(float a, float b) {
    return a + b;
}
```

To compile this C function we can do:

```
$ or1k-elf-gcc -O2 addf.c -c -o addf-sf.o
$ or1k-elf-gcc -O2 -mhard-float addf.c -c -o addf-hf.o
```

Assembly output of `addf-sf.o` contains the software floating point
implementation as we can see below.  This is the default for the
OpenRISC toolchain.  We can see below that a call to `__addsf3` was
added to perform our floating point operation.  The function `__addsf3`
is [provided](https://gcc.gnu.org/onlinedocs/gccint/Soft-float-library-routines.html)
by `libgcc` as a software implementation of the single precision
floating point (`sf`) add operation.

```
$ or1k-elf-objdump -dr addf-sf.o 

Disassembly of section .text:

00000000 <addf>:
   0:   9c 21 ff fc     l.addi r1,r1,-4
   4:   d4 01 48 00     l.sw 0(r1),r9
   8:   04 00 00 00     l.jal 8 <addf+0x8>
                        8: R_OR1K_INSN_REL_26   __addsf3
   c:   15 00 00 00     l.nop 0x0
  10:   85 21 00 00     l.lwz r9,0(r1)
  14:   44 00 48 00     l.jr r9
  18:   9c 21 00 04     l.addi r1,r1,4
```

The disassembly of the `addf-hf.o` below shows that the FPU instruction
(hardware) `lf.add.s` is used to perform addition.  One could imagine if this is
supported it would be more efficient compared to the software implementation.

```
$ or1k-elf-objdump -dr addf-hf.o 

Disassembly of section .text:

00000000 <addf>:
   0:   c9 63 20 00     lf.add.s r11,r3,r4
   4:   44 00 48 00     l.jr r9
   8:   15 00 00 00     l.nop 0x0
```

So if the OpenRISC toolchain already has support for FPU instructions what does
it mean to add glibc support?

When we need to add support for FPU to the OS it means that user land code can
transparently use the FPU.  This also means that multiple processes can share
the single FPU transparently, without other processes realizing it.  So this
boils down to:

 - Kernel to Save and Restore process FPU state during context switches
 - Kernel to handle FPU exceptions and deliver signals to user land
 - Teach GLIBC how to setup FPU rounding mode
 - Teach GLIBC how to translate FPU exceptions
 - Tell GCC and GLIBC soft float about our FPU quirks

## Fixing Architecture Issues

As we started to work on the floating point support we found two issues:

 - The OpenRISC floating point control and status register (FPCSR) is accessible only in
   supervisor mode.
 - We have not defined how the FPU should perform tininess detection.

### FPCSR Access

The GLIBC OpenRISC FPU port, or any port for that matter, starts
by looking at what other architectures have done.  For GLIBC FPU support we can
look at what MIPS, ARM, RISC-V etc. have implemented.  Most ports have a file
called `sysdeps/{arch}/fpu_control.h`, I noticed one thing right away as I went
through this, we can look at ARM or MIPS for example:

[sysdeps/mips/fpu_control.h](https://sourceware.org/git/?p=glibc.git;a=blob;f=sysdeps/mips/fpu_control.h;h=d9ab3195bbef0159bf663c720485f8a3bdfbd136;hb=HEAD#l124):
*Excerpt from the MIPS port showing the definition of _FPU_GETCW and _FPU_SETCW*

```c
 #else
 # define _FPU_GETCW(cw) __asm__ volatile ("cfc1 %0,$31" : "=r" (cw))
 # define _FPU_SETCW(cw) __asm__ volatile ("ctc1 %0,$31" : : "r" (cw))
 #endif
```

[sysdeps/arm/fpu_control.h](https://sourceware.org/git/?p=glibc.git;a=blob;f=sysdeps/arm/fpu_control.h;h=cadbe927b3df20d06f6f4cf159c94e865a595885;hb=HEAD#l67):
*Excerpt from the ARM port showing the definition of _FPU_GETCW and _FPU_SETCW*

```c
 # define _FPU_GETCW(cw) \
   __asm__ __volatile__ ("vmrs %0, fpscr" : "=r" (cw))
 # define _FPU_SETCW(cw) \
   __asm__ __volatile__ ("vmsr fpscr, %0" : : "r" (cw))
 #endif
```

What we see here is a macro that defines how to read or write the floating point
control word for each architecture.  The macros are implemented using a single
assembly instruction.

In OpenRISC we have similar instructions for reading and writing the floating
point control register (FPCSR), writing for example is: `l.mtspr r0,%0,20`.  However,
**on OpenRISC the FPCSR is read-only when running in user-space**, this is a
problem.

If we remember from our operating system studies, user applications run in
[user-mode](https://en.wikipedia.org/wiki/User_space_and_kernel_space) as
apposed to the privileged kernel-mode.
The user [floating point environment](https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/fenv.h.html)
is defined by POSIX in the ISO C Standard.  The C library provides functions to
set rounding modes and clear exceptions using for example
[fesetround](https://pubs.opengroup.org/onlinepubs/9699919799/functions/fesetround.html)
for setting FPU rounding modes and
[feholdexcept](https://pubs.opengroup.org/onlinepubs/9699919799/functions/feholdexcept.html) for clearing exceptions.

Originally [OpenRISC architecture specification](https://openrisc.io/architecture)
specified the floating point control and status registers (FPCSR) as being
read only when executing in user mode, again **this is a problem**.

Other architectures define the floating point control register as being writable in user-mode.
For example, ARM has the
[FPCR and FPSR](https://developer.arm.com/documentation/ddi0502/g/programmers-model/aarch64-register-descriptions/floating-point-control-register),
and RISC-V has the
[FCSR](https://riscv.org/wp-content/uploads/2017/05/riscv-privileged-v1.10.pdf)
all of which are writable in user-mode.

### Tininess Detection

I am skipping ahead a bit here, once the OpenRISC GLIBC port was working we noticed
many problematic math test failures.  This turned out to be inconsistencies
between the tininess detection [[pdf]](https://ntrs.nasa.gov/api/citations/19960008463/downloads/19960008463.pdf).
settings in the toolchain.  Tininess detection and be selected by an FPU
implementation as being done before or after rounding.
In the toolchain this is configured by:

  - GLIBC `TININESS_AFTER_ROUNDING` - macro used by test suite to control
    expectations
  - GLIBC `_FP_TININESS_AFTER_ROUNDING` - macro used to control softfloat
    implementation in GLIBC.
  - GCC libgcc `_FP_TININESS_AFTER_ROUNDING` - macro used to control softfloat
    implementation in GCC libgcc.

### Updating the Spec

Writing to FPCSR from user-mode could be worked around in OpenRISC by
introducing a syscall, but we decided to just change the architecture
specification for this.  Updating the spec keeps it similar to all other
architectures out there.

In OpenRISC we have defined tininess detection to be done before rounding as
this matches what existing FPU implementation have done.

As of architecture specification [revision
1.4](https://openrisc.io/revisions/r1.4) the FPCSR is defined as being writable
in user-mode and we have documented tininess detection to be before rounding.

## Summary
