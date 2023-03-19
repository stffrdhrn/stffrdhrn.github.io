---
title: OpenRISC FPU Port
layout: post
date: 2023-03-19 06:06
categories: [ hardware, embedded, openrisc ]
---

Last year the big milestone for OpenRISC was getting the glibc port upstream.
Though there is libc support for OpenRISC before with MUSL and ucLibc the glibc
port provides a extensive testsuite which provided useful in shaking out toolchain
and OS bugs.

The first version of the glibc port had FPU support as it was dropped due to
missing support in Linux and issues with the architecture specification.

Getting the FPU port working required a cross cutting effort accross the architecture
fullstack from:

 - Architecture Specification
 - Simulators and CPU implementations
 - Linux Kernel
 - GCC Instructions and Soft FPU
 - Binutils/GDB Debugging Support
 - GLIBC Support

## What is FPU Porting?

The FPU in modern CPU's allows CPU's to do floating point math like addition,
substraction, multiplication.  However when used in an OS the FPU's function
becomes more of a math accelarator.  Not all FPU's provide the same set of FPU
operations nor do they have to.

The GCC port for OpenRISC was done a while back.  This add support for
converting c code to FPU instructions.

C code example
Compile
Assembly output

When we need to add support for FPU to the OS it means that user land code
and use the FPU which basically boils down to.

 - Kernel to Save and Restore FPU state during context switches
 - Kernel to handle FPU exceptions
 - Teach GLIBC how to setup FPU rounding mode
 - Teach GLIBC how to translate FPU exceptions
 - Tell GCC and GLIBC soft float about our FPU quirks

With this the toolchain can then use the FPU to accelarate routings like
expf, sin, cos.
