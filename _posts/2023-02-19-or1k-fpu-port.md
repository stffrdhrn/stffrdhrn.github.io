---
title: OpenRISC FPU Port - What is It?
layout: post
date: 2023-03-19 06:06
categories: [ hardware, embedded, openrisc ]
---

Last year the big milestone for OpenRISC was getting the [glibc port upstream](https://openrisc.io/toolchain/2022/02/19/glibc-upstream).
Though there is [libc](https://en.wikipedia.org/wiki/C_standard_library) support for
OpenRISC already with [musl](https://www.musl-libc.org) and [ucLibc](https://uclibc-ng.org)
the glibc port provides a extensive testsuite which provided useful in shaking out toolchain
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

## What is FPU Porting?

The FPU in modern CPU's allows CPU's to do floating point math like addition,
subtraction, multiplication.  When used in an application the FPU's
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

The disassembly of the `addf-hf.o` below shows that the CPU instruction
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

When we need to add support for FPU to the OS it means that user land code
can transparently use the FPU.  This also means that multiple processes must be
able to share the FPU without other processes realizing it.  So this boils down
to:

 - Kernel to Save and Restore process FPU state during context switches
 - Kernel to handle FPU exceptions and deliver signals to user land
 - Teach GLIBC how to setup FPU rounding mode
 - Teach GLIBC how to translate FPU exceptions
 - Tell GCC and GLIBC soft float about our FPU quirks

## First Step Specification

Does the spec have any issues?
