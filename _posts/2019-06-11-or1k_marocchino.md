---
title: OR1K Marocchino in Action
layout: post
date: 2019-06-11 01:37
categories: [ hardware, embedded, openrisc ]
---

In the beginning of 2019 I had finished the [OpenRISC GCC port](/software/embedded/openrisc/2018/02/03/openrisc_gcc_rewrite.html)
and was working on building up toolchain test and verification support using the
[mor1kx](https://github.com/openrisc/mor1kx) soft core.  Part of the mor1kx's
feature set is the ability to swap out different pipeline arrangements to
configure the CPU for performance or resource usage. Each pipeline is named
after an Italian coffee, we have Cappuccino, Espresso and Pronto-Espresso.  One
of these pipelines which has been under development but never integrated into
the main branch was the Marocchino.  I had never paid much attention to the
Marocchino pipeline.

Around the same time the author of Marocchino sent a mail mentioning he could
not used the new GCC port as is was missing Single and Double precision FPU
support.  Using the new verification pipeline I set out to start working on
adding single and double precision floating point support to the OpenRISC gcc
port.  My verification target would be the Marocchino pipeline.

After some initial investigation I found this CPU was much more than a new pipeline
for the mor1kx with a special FPU.  The marocchino has morphed into a complete re-implementation
of the [OpenRISC 1000 spec](https://openrisc.io/architecture).  Seeing this we split
the marocchino out to it's [own repository](https://github.com/openrisc/or1k_marocchino)
where it could grow on it's own.  Of course maintaining the Italian coffee name.

With features like out-of-order execution using
[Tomasulo's algorithm](https://en.wikipedia.org/wiki/Tomasulo_algorithm), 64-bit
FPU operations using register pairs, MMU, Instruction caches, Data caches,
Multicore support and a clean verilog code base the Marocchino is advanced to
say the least.

I would claim Marocchino is one of the most advanced implementations of a
out-of-order execution open source CPU cores.  One of it's friendly rivals is the
[BOOM](https://boom-core.org) core a 64-bit [risc-v](https://riscv.org)
implementation written in Chisel.  To contrast Marocchino has a similar feature
set but is 32-bit OpenRISC written in verilog making it approachable.  If you
know more out-of-order execution open source cores I would love to know, and I
can update this list.

In this series of posts I would like to take the reader over some key parts
of the Marocchino architecture.

  - Marocchino in Action - A guide to getting started with marocchino
  - Data Flows - An deep dive into how the Marocchino pipeline is structured
  - A Tomasulo Implementation - How the Marocchino achieves Out-of-Order execution

Let's get started.

## Marocchino in Action

We can quickly get started with Marocchino as we use
[FuseSoC](https://github.com/olofk/fusesoc).  Which makes bringing together an
running verilog libraries, or cores, a snap.
 
### Environment Setup

The Marocchino development environment requires Linux, you can use a VM, docker
or your own machine.  I personally use fedora and maintain several Debian docker
images for continuous integration and package builds.

The environment we install allows for simulating verilog using
[icarus](http://iverilog.icarus.com) or
[verilator](https://www.veripool.org/wiki/verilator).  It also allows synthesis
and programming to an FPGA using EDA tools.  Here we will cover only simulation.
For details on programming an SoC to an FPGA using FuseSoC see the `build` and
`pgm` commands in [FuseSoC documentation](https://fusesoc.readthedocs.io/en/master/).

**Note** Below we use `/tmp/openrisc` to install software and work on code, but
you can use any path you like.
#### Setting up FuseSoC

![fusesoc logo](/content/2019/fusesoc.png)

To get started let's setup *FuseSoC* and install the required cores into the
FuseSoC library. 

Here we clone the git repositories used for Marocchino development into
`/tmp/openrisc/src` feel free to have a look,  If you feel adventurous make some
changes.  The repos include:
 - [mor1kx-generic](https://github.com/stffrdhrn/mor1kx-generic) - the SoC
   system configuration which wires together the CPU, Memory, System bus, UART
   and a simple interrupt peripheral.
 - [or1k_marocchino](https://github.com/openrisc/or1k_marocchino) - the
   Marocchino CPU core.

```
sudo pip install fusesoc

mkdir -p /tmp/openrisc/src
cd /tmp/openrisc/src
git clone https://github.com/stffrdhrn/mor1kx-generic.git
git clone https://github.com/openrisc/or1k_marocchino.git

# As yourself
fusesoc init -y
fusesoc library add intgen https://github.com/stffrdhrn/intgen.git
fusesoc library add mor1kx-generic /tmp/openrisc/src/mor1kx-generic
fusesoc library add or1k_marocchino /tmp/openrisc/src/or1k_marocchino
```

#### Setting up Icarus Verilog

Next we will need to install our verilog compiler/simulator Icarus Verilog (*iverilog*).

```
mkdir -p /tmp/openrisc/iverilog
cd /tmp/openrisc/iverilog
git clone https://github.com/steveicarus/iverilog.git .

sh autoconf.sh
./configure --prefix=/tmp/openrisc/local
make
make install
export PATH=/tmp/openrisc/local/bin:$PATH
```

#### Using Docker

If you want to get started very quickly faster we can use the
[librecores-ci](https://github.com/librecores/docker-images/tree/master/librecores-ci)
docker image.  Which includes *iverilog*, *verilator* and *fusesoc*.

This allows us to skip the *Setting up Icarus Verilog* and part of the *Setting
up FuseSoC* step above.

This can be done with the following.

```
docker pull librecores/librecores-ci
docker run -it --rm docker.io/librecores/librecores-ci
```

#### Setting up GCC

Next we install the GCC toolchain which is used for compiling C and OpenRISC
assembly programs.  The produced elf binaries can be loaded and run on the CPU core.
Pull the latest toolchain from my gcc
[releases](https://github.com/stffrdhrn/gcc/releases) page.  Here we use the
[newlib](https://sourceware.org/newlib/) (baremetal) toolchain which allows
compiling programs which run directly on the processor.  For details on other
toolchains available see [the toolchain summary](https://openrisc.io/software)
on the OpenRISC homepage.

```
mkdir -p /tmp/openrisc
cd /tmp/openrisc
wget https://github.com/stffrdhrn/gcc/releases/download/or1k-9.1.1-20190507/or1k-elf-9.1.1-20190507.tar.xz

tar -xf or1k-elf-9.1.1-20190507.tar.xz
export PATH=/tmp/openrisc/or1k-elf/bin:$PATH
```

The development environment should now be set up.

To check everything works you should be able to run the following commands.

#### Setup Verification

To ensure the toolchain is installed and working we can run the following:

```
$ or1k-elf-gcc --version
or1k-elf-gcc (GCC) 9.1.1 20190503
Copyright (C) 2019 Free Software Foundation, Inc.
This is free software; see the source for copying conditions.  There is NO
warranty; not even for MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
```

To ensure FuseSoC and the required cores are installed we can run this:

```
$ fusesoc core-info mor1kx-generic 
CORE INFO
Name:      ::mor1kx-generic:1.1
Core root: /root/.local/share/fusesoc/mor1kx-generic

Targets:
marocchino_tb
mor1kx_tb

$ fusesoc list-cores
...
::intgen:0                     : local
::or1k_marocchino:5.0-r3       : local
...

```

### Running an Assembly Program

The most simple program you can run on OpenRISC is a simple assembly program.
When running, everything in the below program is loaded into the OpenRISC
memory, nothing more nothing less.

To compile, run and trace a simple assembly program we do the following.

Create a source file `asm-openrisc.s` as follows:

```
/* Exception vectors section.  */
	.section .vectors, "ax"

/* 0x100: OpenRISC RESET reset vector. */
        .org 0x100 	

	/* Jump to program initialisation code */
	.global _main
	l.movhi r4, hi(_main)
	l.ori r4, r4, lo(_main)
	l.jr    r4
	 l.nop

/* Main executable section.  */
	.section .text
	
	.global _main	
_main:
	l.addi	r1,r0,0x1
	l.addi	r2,r1,0x2
	l.addi	r3,r2,0x4
	l.addi	r4,r3,0x8
	l.addi	r5,r4,0x10
	l.addi	r6,r5,0x20
	l.addi	r7,r6,0x40
	l.addi	r8,r7,0x80
	l.addi	r9,r8,0x100
	l.addi	r10,r9,0x200
	l.addi	r11,r10,0x400
	l.addi	r12,r11,0x800
	l.addi	r13,r12,0x1000
	l.addi	r14,r13,0x2000
	l.addi	r15,r14,0x4000
	l.addi	r16,r15,0x8000

	l.sub	r31,r0,r1
	l.sub	r30,r31,r2
	l.sub	r29,r30,r3
	l.sub	r28,r29,r4
	l.sub	r27,r28,r5
	l.sub	r26,r27,r6
	l.sub	r25,r26,r7
	l.sub	r24,r25,r8
	l.sub	r23,r24,r9
	l.sub	r22,r23,r10
	l.sub	r21,r22,r11
	l.sub	r20,r21,r12
	l.sub	r19,r20,r13
	l.sub	r18,r19,r14
	l.sub	r17,r18,r15
	l.sub	r16,r17,r16

	/* Set sim return code to 0 - meaning OK. */
	l.movhi	r3, 0x0
	l.nop 	0x1 /* Exit simulation */
	l.nop
	l.nop
```

To compile this we use `or1k-elf-gcc`.  Note the `-nostartfiles` option, this is
useful for compiling assembly when we don't need
[newlib/libgloss](https://sourceware.org/git/gitweb.cgi?p=newlib-cygwin.git;a=tree;f=libgloss/or1k)
provided "startup" sections linked into the binary as we provide them ourselves.

```
mkdir /tmp/openrisc/src
cd /tmp/openrisc/src
vim openrisc-asm.s

or1k-elf-gcc -nostartfiles openrisc-asm.s -o openrisc-asm
```

Finally, to run the program on the Marocchino we run `fusesoc` with the below
options.

 - `run` - specifies that we want to run a simulation.
 - `--target` - is a FuseSoC option for `run` specifying which of the
   mor1kx-generic targets we want to run, here we specify `marochino_tb`, the
   Marocchino test bench.
 - `--tool` - is a sub option for `--target` specifying that we want to run the
   marocchino_tb target using icarus.
 - `::mor1kx-generic:1.1` - specifies which system we want to run.  System's
   represent an SoC that can be simulated or synthesized.  You can see a list of
   system using `list-cores`.
 - `--elf-load` - is `mor1kx-generic` specific option which specifies an elf
   binary that will be loaded into memory before the simulation starts.
 - `--trace_enable` - is a `mor1kx-generic` specific option enabling tracing.
   When specified the simulator will output a trace file to `{fusesoc-builds}/mor1kx-generic_1.1/marocchino_tb-icarus/marocchino-trace.log` [see log](/content/2019/asm-marocchino-trace.log).
 - `--trace_to_screen` - is a `mor1kx-generic` specific option enabling tracing
   instruction execution to the console as we can see below.
 - `--vcd` - is a `mor1kx-generic` option instruction icarus to output a vcd
   file which creates a trace file which can be loaded with [gtkwave](http://gtkwave.sourceforge.net).

```
fusesoc run --target marocchino_tb --tool icarus ::mor1kx-generic:1.1 \
  --elf-load ./openrisc-asm --trace_enable --trace_to_screen --vcd

VCD info: dumpfile testlog.vcd opened for output.
Program header 0: addr 0x00000000, size 0x000001A0
elf-loader: /tmp/openrisc/src/openrisc-asm was loaded
Loading         104 words
                   0 : Illegal Wishbone B3 cycle type (xxx)
S 00000100: 18800000 l.movhi r4,0x0000       r4         = 00000000  flag: 0
S 00000104: a8840110 l.ori   r4,r4,0x0110    r4         = 00000110  flag: 0
S 00000108: 44002000 l.jr    r4                                     flag: 0
S 0000010c: 15000000 l.nop   0x0000                                 flag: 0
S 00000110: 9c200001 l.addi  r1,r0,0x0001    r1         = 00000001  flag: 0
S 00000114: 9c410002 l.addi  r2,r1,0x0002    r2         = 00000003  flag: 0
S 00000118: 9c620004 l.addi  r3,r2,0x0004    r3         = 00000007  flag: 0
S 0000011c: 9c830008 l.addi  r4,r3,0x0008    r4         = 0000000f  flag: 0
S 00000120: 9ca40010 l.addi  r5,r4,0x0010    r5         = 0000001f  flag: 0
S 00000124: 9cc50020 l.addi  r6,r5,0x0020    r6         = 0000003f  flag: 0
S 00000128: 9ce60040 l.addi  r7,r6,0x0040    r7         = 0000007f  flag: 0
S 0000012c: 9d070080 l.addi  r8,r7,0x0080    r8         = 000000ff  flag: 0
S 00000130: 9d280100 l.addi  r9,r8,0x0100    r9         = 000001ff  flag: 0
S 00000134: 9d490200 l.addi  r10,r9,0x0200   r10        = 000003ff  flag: 0
S 00000138: 9d6a0400 l.addi  r11,r10,0x0400  r11        = 000007ff  flag: 0
S 0000013c: 9d8b0800 l.addi  r12,r11,0x0800  r12        = 00000fff  flag: 0
S 00000140: 9dac1000 l.addi  r13,r12,0x1000  r13        = 00001fff  flag: 0
S 00000144: 9dcd2000 l.addi  r14,r13,0x2000  r14        = 00003fff  flag: 0
S 00000148: 9dee4000 l.addi  r15,r14,0x4000  r15        = 00007fff  flag: 0
S 0000014c: 9e0f8000 l.addi  r16,r15,0x8000  r16        = ffffffff  flag: 0
S 00000150: e3e00802 l.sub   r31,r0,r1       r31        = ffffffff  flag: 0
S 00000154: e3df1002 l.sub   r30,r31,r2      r30        = fffffffc  flag: 0
S 00000158: e3be1802 l.sub   r29,r30,r3      r29        = fffffff5  flag: 0
S 0000015c: e39d2002 l.sub   r28,r29,r4      r28        = ffffffe6  flag: 0
S 00000160: e37c2802 l.sub   r27,r28,r5      r27        = ffffffc7  flag: 0
S 00000164: e35b3002 l.sub   r26,r27,r6      r26        = ffffff88  flag: 0
S 00000168: e33a3802 l.sub   r25,r26,r7      r25        = ffffff09  flag: 0
S 0000016c: e3194002 l.sub   r24,r25,r8      r24        = fffffe0a  flag: 0
S 00000170: e2f84802 l.sub   r23,r24,r9      r23        = fffffc0b  flag: 0
S 00000174: e2d75002 l.sub   r22,r23,r10     r22        = fffff80c  flag: 0
S 00000178: e2b65802 l.sub   r21,r22,r11     r21        = fffff00d  flag: 0
S 0000017c: e2956002 l.sub   r20,r21,r12     r20        = ffffe00e  flag: 0
S 00000180: e2746802 l.sub   r19,r20,r13     r19        = ffffc00f  flag: 0
S 00000184: e2537002 l.sub   r18,r19,r14     r18        = ffff8010  flag: 0
S 00000188: e2327802 l.sub   r17,r18,r15     r17        = ffff0011  flag: 0
S 0000018c: e2118002 l.sub   r16,r17,r16     r16        = ffff0012  flag: 0
S 00000190: 18600000 l.movhi r3,0x0000       r3         = 00000000  flag: 0
S 00000194: 15000001 l.nop   0x0001                                 flag: 0
exit(0x00000000);
```

If we look at [the VCD trace file](/content/2019/asm-testlog.vcd) in gtkwave we
can see the below trace.  The trace file is also helpful for navigating through
the various SoC components as it captures all wire transitions.

Take note that we are not seeing very good performance, this is because caching is
not enabled and the CPU takes several cycles to read an instruction from memory.
This means we are not seeing one instruction executed per cycle.  Enabling
caches would fix this.

![asm tracefile vcd](/content/2019/asm-marocchino-trace.png)

### Running a C program

When we compile a C program there is a lot more happening behind the scenes.
The linker will link in an entire runtime that along with standard libc
functions on OpenRISC will setup interrupt vectors, enable caches, setup memory
sections for variables, run static initializers and finally run our program.

The program:

```
/* Simple c program, doing some math.  */

#include <stdio.h>

int a [] = { 1, 2, 3, 4, 5 };

int madd(int a, int b, int c) {
  return a * b + c;
}

int main() {
  int res;

  for (int i = 0; i < 5; i++) {
    res = madd(0  , a[1], a[i]);
    res = madd(res, a[2], a[i]);
    res = madd(res, a[2], a[i]);
    res = madd(res, a[3], a[i]);
    res = madd(res, a[4], a[i]);
  }

  printf("Result is = %d\n", res);

  return 0;
}
```

To compile we use the below `or1k-elf-gcc` command.  Notice, we do not specify
`-nostartfiles` here as we do want newlib to link in all the start routines to
provide a full c runtime.  We do specify the following arguments to tell GCC a
bit about our OpenRISC cpu.  If these `-m` options are not specified GCC will
link in code using the libgcc library to emulate these instructions.

  - `-mhard-mul` - indicates that our cpu target supports multiply instructions.
  - `-mhard-div` - indicates that our cpu target supports divide instructions.
  - `-mhard-float` - indicates that our cpu target supports FPU instructions.
  - `-mdouble-float` - indicates that our cpu target supports the new double precision floating point instructions using register pairs ([orfpx64a32](https://openrisc.io/proposals/orfpx64a32)).

To see a full list of options for OpenRISC read the GCC manual or see the output
of `or1k-elf-gcc --target-help`.

```
or1k-elf-gcc -Wall -O2 -mhard-mul -mhard-div -mhard-float -mdouble-float -mror \
  openrisc-c.c -o openrisc-c
```

If we want to inspect the assembly to ensure we did generate multiply instructions
we can use the trusty `objdump` utility.  As per below, yes, we can see multiply
instructions.

```
or1k-elf-objdump -d openrisc-c | grep -A10 main
00002000 <main>:
    2000:       1a a0 00 01     l.movhi r21,0x1
    2004:       9c 21 ff f8     l.addi r1,r1,-8
    2008:       9e b5 40 3c     l.addi r21,r21,16444
    200c:       86 75 00 10     l.lwz r19,16(r21)
    2010:       86 f5 00 08     l.lwz r23,8(r21)
    2014:       e2 37 9b 06     l.mul r17,r23,r19
    2018:       e2 31 98 00     l.add r17,r17,r19
    201c:       e2 31 bb 06     l.mul r17,r17,r23
    2020:       e2 31 98 00     l.add r17,r17,r19
    2024:       86 b5 00 0c     l.lwz r21,12(r21)
...

```

Similar to running the assembly example we can run this with `fusesoc` as follows.

```
fusesoc run --target marocchino_tb --tool icarus ::mor1kx-generic:1.1 \
  --elf-load ./openrisc-c --trace_enable --vcd
...

Result is = 1330
```

Now, if we look at [the VCD trace file](/content/2019/c-testlog.vcd) we can see the
below trace.  Notice that with the c program we can observe better pipelining where
an instruction can be executed every clock cycle.  This is because caches have
been initialized as part of the newlib c-runtime initialization, Great!

![c tracefile vcd](/content/2019/c-marocchino-trace.png)

## Conclusion

In this article we went through a quick introduction to the Marocchino
development environment.  The development environment would actually be similar
when developing any OpenRISC core. 

This environment will allow the reader to following in future Marocchino
articles where we go deeper into the architecture.  In this environment you can
now:
  - Develop and test verilog code for the Marocchino processor
  - Develop assembly programs and test them on the Marocchino or other OpenRISC processors
  - Develop c programs and test them on the Marocchino or other OpenRISC processors

In the next article we will look more into how the above programs actually flow
through the Marocchino pipeline.  Stay tuned.
