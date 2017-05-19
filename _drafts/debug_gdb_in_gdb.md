---
title: Debugging GDB in GDB
layout: post
date: 2017-05-19
---

For the last year or so I have been working on getting a [gdb port
upstreamed][1] for [OpenRISC][2].  One thing one sometimes has to do when working
on gdb is to debug it.  Debugging gdb with gdb, it could be a bit
confusing, these tips should help.

## Setting the Prompt

Setting the prompt of the parent gdb will help so you know which gdb you
are in by looking at the command line.  I do that with `set prompt
(master:gdb) `, (having space after the `(master:gdb)` is recommended).

## Handling SIGINT

Handling `ctrl-c` is another thing we need to consider.  If you are in your
inferior gdb and you press `ctrl-c` which gdb will you stop?  The parent
gdb or the inferior gdb?

The parent gdb will be stopped.  If we then `continue` the inferior will
continue.  If we want to have the inferior stop as well we can set `handle
SIGINT pass`.

## All together

An example session may look like the following

```
(gdb) set prompt (master:gdb)

(master:gdb) handle SIGINT
SIGINT is used by the debugger.
Are you sure you want to change it? (y or n) y
Signal        Stop      Print   Pass to program Description
SIGINT        Yes       Yes     No              Interrupt

(master:gdb) handle SIGINT pass
SIGINT is used by the debugger.
Are you sure you want to change it? (y or n) y
Signal        Stop      Print   Pass to program Description
SIGINT        Yes       Yes     Yes             Interrupt

(master:gdb) run
Starting program: /opt/shorne/software/or1k/bin/or1k-elf-gdb
(gdb) file loop.nelib
Reading symbols from loop.nelib...done.

(gdb) target sim
Connected to the simulator.

(gdb) load
Loading section .vectors, size 0x2000 lma 0x0
Loading section .init, size 0x28 lma 0x2000
Loading section .text, size 0x4f88 lma 0x2028
Loading section .fini, size 0x1c lma 0x6fb0
Loading section .rodata, size 0x18 lma 0x6fcc
Loading section .eh_frame, size 0x4 lma 0x8fe4
Loading section .ctors, size 0x8 lma 0x8fe8
Loading section .dtors, size 0x8 lma 0x8ff0
Loading section .jcr, size 0x4 lma 0x8ff8
Loading section .data, size 0xc74 lma 0x8ffc
Start address 0x100
Transfer rate: 254848 bits in <1 sec.

(gdb) run
Starting program: /home/shorne/work/openrisc/loop.nelib
loop
^C
Program received signal SIGINT, Interrupt.
or1k32bf_engine_run_fast (current_cpu=0x7fffee59c010) at mloop.c:577
577       if (! CPU_IDESC_SEM_INIT_P (current_cpu))
Missing separate debuginfos, use: dnf debuginfo-install expat-2.2.0-1.fc25.x86_64 libgcc-6.3.1-1.fc25.x86_64 libstdc++-6.3.1-1.fc25.x86_64 ncurses-libs-6.0-6.20160709.fc25.x86_64 python-libs-2.7.13-1.fc25.x86_64 xz-libs-5.2.2-2.fc24.x86_64 zlib-1.2.8-10.fc24.x86_64

(master:gdb) bt
#0  or1k32bf_engine_run_fast (current_cpu=0x7fffee59c010) at mloop.c:577
#1  0x0000000000654395 in engine_run_1 (fast_p=1, max_insns=<optimized out>, sd=0xd68a60) at ../../../binutils-gdb/sim/or1k/../common/cgen-run.c:191
#2  sim_resume (sd=0xd68a60, step=0, siggnal=<optimized out>) at ../../../binutils-gdb/sim/or1k/../common/cgen-run.c:108
#3  0x00000000004392d1 in gdbsim_wait (ops=<optimized out>, ptid=..., status=0x7fffffffc910, options=<optimized out>) at ../../binutils-gdb/gdb/remote-sim.c:1015
#4  0x0000000000600c6d in delegate_wait (self=<optimized out>, arg1=..., arg2=<optimized out>, arg3=<optimized out>) at ../../binutils-gdb/gdb/target-delegates.c:138
#5  0x000000000060ff64 in target_wait (ptid=..., status=status@entry=0x7fffffffc910, options=options@entry=0) at ../../binutils-gdb/gdb/target.c:2292
#6  0x000000000057e9d9 in do_target_wait (ptid=..., status=status@entry=0x7fffffffc910, options=0) at ../../binutils-gdb/gdb/infrun.c:3618
#7  0x0000000000589658 in fetch_inferior_event (client_data=<optimized out>) at ../../binutils-gdb/gdb/infrun.c:3910
#8  0x0000000000548b1c in check_async_event_handlers () at ../../binutils-gdb/gdb/event-loop.c:1064
#9  gdb_do_one_event () at ../../binutils-gdb/gdb/event-loop.c:326
#10 0x0000000000548c05 in gdb_do_one_event () at ../../binutils-gdb/gdb/common/common-exceptions.h:221
#11 start_event_loop () at ../../binutils-gdb/gdb/event-loop.c:371
#12 0x000000000059be78 in captured_command_loop (data=data@entry=0x0) at ../../binutils-gdb/gdb/main.c:325
#13 0x000000000054ab73 in catch_errors (func=func@entry=0x59be50 <captured_command_loop(void*)>, func_args=func_args@entry=0x0, errstring=errstring@entry=0x711a00 "", mask=mask@entry=RETURN_MASK_ALL)
    at ../../binutils-gdb/gdb/exceptions.c:236
#14 0x000000000059cda6 in captured_main (data=0x7fffffffca60) at ../../binutils-gdb/gdb/main.c:1150
#15 gdb_main (args=args@entry=0x7fffffffcb90) at ../../binutils-gdb/gdb/main.c:1160
#16 0x000000000040c265 in main (argc=<optimized out>, argv=<optimized out>) at ../../binutils-gdb/gdb/gdb.c:32

(master:gdb) c
Continuing.

Program received signal SIGINT, Interrupt.
main () at loop.c:22
22          while (1) { ; }

(gdb) bt
#0  main () at loop.c:22

(gdb) l
17        tdata.str = "loop";
18        foo(tdata);
19
20        while (1) {
21          printf("%s\n", tdata.str);
22          while (1) { ; }
23        }
24        return 0;
25      }

(gdb) q
A debugging session is active.

        Inferior 1 [process 42000] will be killed.

Quit anyway? (y or n) y
[Inferior 1 (process 24876) exited normally]
(master:gdb) q

```

## Other Options

You could also remote debug from a different terminal by using `attach` to
attach to and debug the secondary.  But I find having everything in one
terminal nice.

## Further References
- [GDB manual - prompt](https://sourceware.org/gdb/current/onlinedocs/gdb/Prompt.html#Prompt)
- [GDB manual - signal](https://sourceware.org/gdb/current/onlinedocs/gdb/Signals.html#Signals)
- [GDB manual - attach](https://sourceware.org/gdb/onlinedocs/gdb/Attach.html#Attach)

[1]: https://sourceware.org/ml/gdb-patches/2017-04/msg00649.html
[2]: http://openrisc.io

