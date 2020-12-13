---
title: Unwinding - How the C++ Exceptions Work
layout: post
started: 2020-11-01
date: 2020-12-13 04:25
categories: [ software, toolchain, openrisc ]
---

I have been working on porting GLIBC to the OpenRISC architecture.  This has
taken longer than I expected as with GLIBC upstreaming we must get every
test to pass.  This was different compared to GDB and GCC which were a
bit more lenient.

My [first upstreaming attempt](https://lists.librecores.org/pipermail/openrisc/2020-May/002602.html)
was completely tested on the [QEMU](https://www.qemu.org) simulator.  I have
since added an FPGA [LiteX SoC](https://github.com/enjoy-digital/litex/blob/master/README.md)
to my test platform options.  LiteX runs Linux on the OpenRISC mor1kx softcore
and tests are loaded over an SSH session.  The SoC eliminates an issue I was
seeing on the simulator where under heavy load it appears the [MMU starves the kernel](https://github.com/openrisc/linux/issues/12)
from getting any work done.

To get to where I am now this required:

- Fixing buggy [LiteETH network driver](https://github.com/litex-hub/linux/commit/78969c54328e35b360d9452c7602f21107a13d22)
  in LiteX.
- Updating the OpenRISC GDB port to [support gdbserver](https://github.com/stffrdhrn/binutils-gdb/commit/9d0d2e9bef5c84caa7f05cc7ddba1e092e2b5120)
  and [native debugging](https://github.com/stffrdhrn/binutils-gdb/commit/82e99d5df56be3b18c63e613d00e2367fb5a78b7)
- Fixing bugs in the [Linux Kernel](https://github.com/stffrdhrn/linux/commit/28b852b1dc351efc6525234c5adfd5bc2ad6d6e1) and
  [GLIBC](https://github.com/stffrdhrn/or1k-glibc/commit/75ddf155968299042e4d2b492e3b547c86d4672e) to get gdbserver and native support working

Adding GDB Linux debugging support is great because it allows debugging of
multithreaded processes and signal handling; which we are going to need.

## A Bug

Our story starts when I was trying to fix is a failing GLIBC [NPTL](https://en.wikipedia.org/wiki/Native_POSIX_Thread_Library)
test case.  The test case involves C++ exceptions and POSIX threads.
The issue is that the `catch` block of a `try/catch` block is not
being called.  Where do we even start?

My plan for approaching test case failures is:
1. Understand what the test case is trying to test and where its failing
2. Create a hypothesis about where the problem is
3. Understand how the failing API's works internally
4. Debug until we find the issue
5. If we get stuck go back to `2.`

Let's have a try.

### Understanding the Test case

The GLIBC test case is [nptl/tst-cancel24.cc](https://sourceware.org/git/?p=glibc.git;a=blob;f=nptl/tst-cancel24.cc;h=1af709a8cab1d422ef4401e2b2d178df86f863c5;hb=HEAD).
The test starts in the `do_test` function and it will create a child thread with `pthread_create`.
The child thread executes function `tf` which waits on a semaphore until the parent thread cancels it.  It
is expected that the child thread, when cancelled , will call it's catch block.

The failure is that the `catch` block is not getting run as evidenced by the `except_caught` variable
not being set to `true`.

Below is an excerpt from the test showing the `tf` function.

```c
static void *
tf (void *arg) {
  sem_t *s = static_cast<sem_t *> (arg);

  try {
      monitor m;

      pthread_barrier_wait (&b);

      while (1)
        sem_wait (s);
  } catch (...) {
      except_caught = true;
      throw;
  }
  return NULL;
}
```

So the `catch` block is not being run.  Simple, but where do we start to
debug that?  Let's move onto the next step.

### Creating a Hypothesis

This one is a bit tricky as it seems C++ `try/catch` blocks are broken. Here, I am
working on GLIBC testing, what does that have to do with C++?

To get a better idea of where the problem is I tried to modify the test to test
some simple ideas. First, maybe there is a problem with catching exceptions
throws from thread child functions.

```c
static void do_throw() { throw 99; }

static void * tf () {
  try {
      monitor m;
      while (1) do_throw();
  } catch (...) {
      except_caught = true;
  }
  return NULL;
}
```

No, this works correctly.  So `try/catch` is working.

> **Hypothesis**: There is a problem handling exceptions while in a syscall.
> There may be something broken with OpenRISC related to how we setup stack
> frames for syscalls that makes the unwinder fail.

How does that work?  Let's move onto the next step.

### Understanding the Internals

To find this bug we need to understand how C++ exceptions work.  Also, we need to know
what happens when a thread is cancelled in a multithreaded
([pthread](https://en.wikipedia.org/wiki/POSIX_Threads)) glibc environment.

There are a few contributors pthread cancellation and C++ exceptions which are:

- **DWARF** - provided by our program and libraries in the `.eh_frame` ELF
  section
- **GLIBC** - provides the pthread runtime and cleanup callbacks to the GCC unwinder code
- **GCC** - provides libraries for dealing with exceptions
  - `libgcc_s.so` - handles unwinding by reading program **DWARF** metadata and doing the frame decoding
  - `libstdc++.so.6` - provides the C++ personality routine which
    identifies and prepares `catch` blocks for execution

#### DWARF
ELF binaries provide debugging information in a data format called
[DWARF](https://en.wikipedia.org/wiki/DWARF).  The name was chosen to maintain a
fantasy theme.  Lately the Linux community has a new debug format called
[ORC](https://lwn.net/Articles/728339/).

Though DWARF is a debugging format and usually stored in `.debug_frame`,
`.debug_info`, etc sections, a stripped down version it is used for exception
handling.

Each ELF binary that supports unwinding contains the `.eh_frame` section to
provide unwinding information.  This can be seen with the `readelf` program.

```
$ readelf -S sysroot/lib/libc.so.6
There are 70 section headers, starting at offset 0xaa00b8:

Section Headers:
  [Nr] Name              Type            Addr     Off    Size   ES Flg Lk Inf Al
  [ 0]                   NULL            00000000 000000 000000 00      0   0  0
  [ 1] .note.ABI-tag     NOTE            00000174 000174 000020 00   A  0   0  4
  [ 2] .gnu.hash         GNU_HASH        00000194 000194 00380c 04   A  3   0  4
  [ 3] .dynsym           DYNSYM          000039a0 0039a0 008280 10   A  4  15  4
  [ 4] .dynstr           STRTAB          0000bc20 00bc20 0054d4 00   A  0   0  1
  [ 5] .gnu.version      VERSYM          000110f4 0110f4 001050 02   A  3   0  2
  [ 6] .gnu.version_d    VERDEF          00012144 012144 000080 00   A  4   4  4
  [ 7] .gnu.version_r    VERNEED         000121c4 0121c4 000030 00   A  4   1  4
  [ 8] .rela.dyn         RELA            000121f4 0121f4 00378c 0c   A  3   0  4
  [ 9] .rela.plt         RELA            00015980 015980 000090 0c  AI  3  28  4
  [10] .plt              PROGBITS        00015a10 015a10 0000d0 04  AX  0   0  4
  [11] .text             PROGBITS        00015ae0 015ae0 155b78 00  AX  0   0  4
  [12] __libc_freeres_fn PROGBITS        0016b658 16b658 001980 00  AX  0   0  4
  [13] .rodata           PROGBITS        0016cfd8 16cfd8 0192b4 00   A  0   0  4
  [14] .interp           PROGBITS        0018628c 18628c 000018 00   A  0   0  1
  [15] .eh_frame_hdr     PROGBITS        001862a4 1862a4 001a44 00   A  0   0  4
  [16] .eh_frame         PROGBITS        00187ce8 187ce8 007cf4 00   A  0   0  4
  [17] .gcc_except_table PROGBITS        0018f9dc 18f9dc 000341 00   A  0   0  1
...
```

We can decode the metadata using `readelf` as well using the
`--debug-dump=frames-interp` and `--debug-dump=frames` arguments.

The `frames` dump provides a raw output of the DWARF metadata for each frame.
This is not usually as useful as `frames-interp`, but it shows how the DWARF
format is actually a bytecode.  The DWARF interpreter needs to execute these
operations to understand how to derive the values of registers based current PC.

There is an interesting talk in [Exploiting the hard-working
DWARF.pdf](https://www.cs.dartmouth.edu/~sergey/battleaxe/hackito_2011_oakley_bratus.pdf).

An example of the `frames` dump:

```
$ readelf --debug-dump=frames sysroot/lib/libc.so.6

...
00016788 0000000c ffffffff CIE
  Version:               1
  Augmentation:          ""
  Code alignment factor: 4
  Data alignment factor: -4
  Return address column: 9

  DW_CFA_def_cfa_register: r1
  DW_CFA_nop

00016798 00000028 00016788 FDE cie=00016788 pc=0016b584..0016b658
  DW_CFA_advance_loc: 4 to 0016b588
  DW_CFA_def_cfa_offset: 4
  DW_CFA_advance_loc: 8 to 0016b590
  DW_CFA_offset: r9 at cfa-4
  DW_CFA_advance_loc: 68 to 0016b5d4
  DW_CFA_remember_state
  DW_CFA_def_cfa_offset: 0
  DW_CFA_restore: r9
  DW_CFA_restore_state
  DW_CFA_advance_loc: 56 to 0016b60c
  DW_CFA_remember_state
  DW_CFA_def_cfa_offset: 0
  DW_CFA_restore: r9
  DW_CFA_restore_state
  DW_CFA_advance_loc: 36 to 0016b630
  DW_CFA_remember_state
  DW_CFA_def_cfa_offset: 0
  DW_CFA_restore: r9
  DW_CFA_restore_state
  DW_CFA_advance_loc: 40 to 0016b658
  DW_CFA_def_cfa_offset: 0
  DW_CFA_restore: r9
```

The `frames-interp` argument is a bit more clear as it shows the interpreted output
of the bytecode.  Below we see two types of entries:
  - `CIE` - Common Information Entry
  - `FDE` - Frame Description Entry

The `CIE` provides starting point information for each child `FDE` entry.  Some
things to point out: we see `ra=9` indicates the return address is stored in
register `r9`,  we see CFA `r1+0` indicates the canonical frame pointer is stored in
register `r1` and we see the stack frame size is `4` bytes.

An example of the `frames-interp` dump:

```
$ readelf --debug-dump=frames-interp sysroot/lib/libc.so.6

...
00016788 0000000c ffffffff CIE "" cf=4 df=-4 ra=9
   LOC   CFA
00000000 r1+0

00016798 00000028 00016788 FDE cie=00016788 pc=0016b584..0016b658
   LOC   CFA      ra
0016b584 r1+0     u
0016b588 r1+4     u
0016b590 r1+4     c-4
0016b5d4 r1+4     c-4
0016b60c r1+4     c-4
0016b630 r1+4     c-4
0016b658 r1+0     u
```

#### GLIBC

GLIBC provides `pthreads` which when used with C++ needs to support exception
handling.  The main place exceptions are used with `pthreads` is when cancelling
threads.  When using `pthread_cancel` a cancel signal is sent to the target thread using [tgkill](https://man7.org/linux/man-pages/man2/tgkill.2.html)
which causes an exception.

This is implemented with the below APIs.

- [sigcancel_handler](https://sourceware.org/git/?p=glibc.git;a=blob;f=nptl/nptl-init.c;h=53b817715d58192857ed14450052e16dc34bc01b;hb=HEAD#l126) -
  Setup during the pthread runtime initialization, it handles cancellation,
  which calls `__do_cancel`, which calls `__pthread_unwind`.
- [__pthread_unwind](https://sourceware.org/git/?p=glibc.git;a=blob;f=nptl/unwind.c;h=8f157e49f4a088ac64722e85ff24514fff7f3c71;hb=HEAD#l121) -
  Is called with `pd->cancel_jmp_buf`.  It calls glibc's `__Unwind_ForcedUnwind`.
- [_Unwind_ForcedUnwind](https://sourceware.org/git/?p=glibc.git;a=blob;f=sysdeps/nptl/unwind-forcedunwind.c;h=50a089282bc236aa644f40feafd0dacdafe3a4e7;hb=HEAD#l122) -
  Loads GCC's `libgcc_s.so` version of `_Unwind_ForcedUnwind`
  and calls it with parameters:
  - `exc` - the exception context
  - `unwind_stop` - the stop callback to GLIBC, called for each frame of the unwind, with
    the stop argument `ibuf`
  - `ibuf` - the `jmp_buf`, created by `setjmp` (`self->cancel_jmp_buf`) in `start_thread`
- [unwind_stop](https://sourceware.org/git/?p=glibc.git;a=blob;f=nptl/unwind.c;h=8f157e49f4a088ac64722e85ff24514fff7f3c71;hb=HEAD#l39) -
  Checks the current state of unwind and call the `cancel_jmp_buf` if
  we are at the end of stack.  When the `cancel_jmp_buf` is called the thread
  exits.

Let's look at `pd->cancel_jmp_buf` in more details.  The `cancel_jmp_buf` is
setup during `pthread_create` after clone in [start_thread](https://sourceware.org/git/?p=glibc.git;a=blob;f=nptl/pthread_create.c;h=bad4e57a845bd3148ad634acaaccbea08b04dbbd;hb=HEAD#l406).
It uses the [setjmp](https://www.man7.org/linux/man-pages/man3/setjmp.3.html) and
[longjump](https://man7.org/linux/man-pages/man3/longjmp.3.html) non local goto mechanism.

Let's look at some diagrams.

![Pthread Normal](/content/2020/pthread-normal-seq.png)

The above diagram shows a pthread that exits normally.  During the *Start* phase
of the thread `setjmp` will create the `cancel_jmp_buf`. After the thread
routine exits it returns to the `start_thread` routine to do cleanup.
The `cancel_jmp_buf` is not used.

![Pthread Signalled](/content/2020/pthread-signalled-seq.png)

The above diagram shows a pthread that exits normally.  When the
thread is created `setjmp` will create the `cancel_jmp_buf`. In this case
while the thread routine is running it is cancelled, the unwinder runs
and at the end it calls `unwind_stop` which calls `longjmp`.  After the
`longjmp` the thread is returned to `start_thread` to do cleanup.

A highly redacted version of our `start_thread` and `unwind_stop` functions is
shown below.

```c
start_thread()
{
  struct pthread *pd = START_THREAD_SELF;
  ...
  struct pthread_unwind_buf unwind_buf;

  int not_first_call;
  not_first_call = setjmp ((struct __jmp_buf_tag *) unwind_buf.cancel_jmp_buf);
  ...
  if (__glibc_likely (! not_first_call))
    {
      /* Store the new cleanup handler info.  */
      THREAD_SETMEM (pd, cleanup_jmp_buf, &unwind_buf);
      ...

      /* Run the user provided thread routine */
      ret = pd->start_routine (pd->arg);
      THREAD_SETMEM (pd, result, ret);
    }
  ... free resources ...
  __exit_thread ();
}
```

```c
unwind_stop (_Unwind_Action actions,
	     struct _Unwind_Context *context, void *stop_parameter)
{
  struct pthread_unwind_buf *buf = stop_parameter;
  struct pthread *self = THREAD_SELF;
  int do_longjump = 0;
  ...

  if ((actions & _UA_END_OF_STACK)
      || ... )
    do_longjump = 1;

  ...
  /* If we are at the end, go back start_thread for cleanup */
  if (do_longjump)
    __libc_unwind_longjmp ((struct __jmp_buf_tag *) buf->cancel_jmp_buf, 1);

  return _URC_NO_REASON;
}
```

#### GCC

GCC provides the exception handling and unwinding capabilities
to the C++ runtime.  They are provided in the `libgcc_s.so` and `libstdc++.so.6` libraries.

The `libgcc_s.so` library implements the [IA-64 Itanium Exception Handling ABI](https://itanium-cxx-abi.github.io/cxx-abi/abi-eh.html).
It's interesting that the now defunct [Itanium](https://en.wikipedia.org/wiki/Itanium#Itanium_9700_(Kittson):_2017)
architecture introduced this ABI which is now the standard for all processor exception
handling.  There are two main entry points for the unwinder are:

- `_Unwind_ForcedUnwind` - for forced unwinding
- `_Unwind_RaiseException` - for raising normal exceptions

*Forced Unwinds*

Exceptions that are raised for thread cancellation use a single phase forced unwind.
Code execution will not resume, but catch blocks will be run.  This is why
[cancel exceptions must be rethrown](https://udrepper.livejournal.com/21541.html).

Forced unwinds use the `unwind_stop` handler which GLIBC provides as explained in
the **GLIBC** section above.

- [_Unwind_ForcedUnwind](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind.inc;h=9acead33ffc01e892d6feda2aaeffd9d04e56e74;hb=HEAD#l201) - calls:
  - [uw_init_context](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind-dw2.c;h=fe896565d2ec5c43ac683f2c6ed6d5e49fd8242e;hb=HEAD#l1558) - load details of the current frame from cpu/stack into CONTEXT
  - [_Unwind_ForcedUnwind_Phase2](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind.inc;h=9acead33ffc01e892d6feda2aaeffd9d04e56e74;hb=HEAD#l144) - do the frame iterations
  - [uw_install_context](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind-dw2.c;h=fe896565d2ec5c43ac683f2c6ed6d5e49fd8242e;hb=HEAD#l1641) - exit unwinder jumping into the selected frame
- `_Unwind_ForcedUnwind_Phase2` - loops forever doing:
  - [uw_frame_state_for](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind-dw2.c;h=fe896565d2ec5c43ac683f2c6ed6d5e49fd8242e;hb=HEAD#l1244) - populate FS for the frame one frame above CONTEXT, searching DWARF using CONTEXT->ra
  - `stop`- callback to GLIBC to stop the unwind if needed
  - `FS.personality` - the C++ personality routine, see below, called with `_UA_FORCE_UNWIND | _UA_CLEANUP_PHASE`
  - [uw_advance_context](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind-dw2.c;h=fe896565d2ec5c43ac683f2c6ed6d5e49fd8242e;hb=HEAD#l1552) - advance CONTEXT by populating it from FS

*Normal Exceptions*

For exceptions raised programmatically unwinding is very similar to the forced unwind, but
there is no `stop` function and exception unwinding is 2 phase.

- [_Unwind_RaiseException](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind.inc;h=9acead33ffc01e892d6feda2aaeffd9d04e56e74;hb=HEAD#l83) - calls:
  - `uw_init_context` - load details of the current frame from cpu/stack into CONTEXT
  - Do phase 1 loop:
    - `uw_frame_state_for` - populate FS for the frame one frame above CONTEXT, searching DWARF using CONTEXT->ra
    - `FS.personality` - the C++ personality routine, see below, called with `_UA_SEARCH_PHASE`
    - [uw_update_context](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind-dw2.c;h=fe896565d2ec5c43ac683f2c6ed6d5e49fd8242e;hb=HEAD#l1516) - advance CONTEXT by populating it from FS (same as `uw_advance_context`)
  - [_Unwind_RaiseException_Phase2](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/unwind.inc;h=9acead33ffc01e892d6feda2aaeffd9d04e56e74;hb=HEAD#l30) - do the frame iterations
  - `uw_install_context` - exit unwinder jumping to selected frame
- `_Unwind_RaiseException_Phase2` - do phase 2, loops forever doing:
  - `uw_frame_state_for` - populate FS for the frame one frame above CONTEXT, searching DWARF using CONTEXT->ra
  - `FS.personality` - the C++ personality routine, called with `_UA_CLEANUP_PHASE`
  - `uw_update_context` - advance CONTEXT by populating it from FS

The `libstdc++.so.6` library provides the [C++ standard library](https://gcc.gnu.org/onlinedocs/gcc-10.2.0/libstdc++/manual/)
which includes the C++ personality routine [__gxx_personality_v0](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libstdc%2B%2B-v3/libsupc%2B%2B/eh_personality.cc;h=fd7cd6fc79886bf17aea6bc713d2a3840aa31326;hb=HEAD#l336).
The personality routine is the interface between the unwind routines and the c++
(or other language) runtime, which handles the exception handling logic for that
language.

As we saw above the personality routine is executed for each stack frame.  The
function checks if there is a `catch` block that matches the exception being
thrown.  If there is a match, it will update the context to prepare it to jump
into the catch routine and return `_URC_INSTALL_CONTEXT`.  If there is no catch
block matching it returns `_URC_CONTINUE_UNWIND`.

In the case of `_URC_INSTALL_CONTEXT` then the `_Unwind_ForcedUnwind_Phase2`
loop breaks and calls `uw_install_context`.

#### Unwinding through a Signal Frame

When the GCC unwinder is looping through frames the `uw_frame_state_for`
function will search DWARF information.  The DWARF lookup will fail for signal
frames and a fallback mechanism is provided for each architecture to handle
this.  For OpenRISC Linux this is handled by
[or1k_fallback_frame_state](https://gcc.gnu.org/git/?p=gcc.git;a=blob;f=libgcc/config/or1k/linux-unwind.h;h=c7ed043d3a89f2db205fd78fcb5db21f6fb561b2;hb=HEAD).
To understand how this works let's look into the Linux kernel a bit.

A process must be context switched to kernel by either a system call, timer or other
interrupt in order to receive a signal.

![The Stack Frame after an Interrupt](/content/2020/stack-frame-int.png)

The diagram above shows what a process stack looks like after the kernel takes over.
An *interrupt frame* is push to the top of the stack and the `pt_regs` structure
is filled out containing the processor state before the interrupt.

![The Stack Frame in a Sig Handler](/content/2020/stack-frame-in-handler.png)

This second diagram shows what happens when a signal handler is invoked.  A new
special *signal frame* is pushed onto the stack and when the process is resumed
it resumes in the signal handler.  In OpenRISC the signal frame is setup by the [setup_rt_frame](https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/arch/openrisc/kernel/signal.c?h=v5.10-rc7#n144)
function which is called inside of `do_signal` which calls `handle_signal`
which calls `setup_rt_frame`.

After the signal handler routine runs we return to a special bit of code called
the **Trampoline**.  The trampoline code lives on the stack and runs
[sigretrun](https://man7.org/linux/man-pages/man2/sigreturn.2.html).

Now back to `or1k_fallback_frame_state`.

The `or1k_fallback_frame_state` function checks if the current frame is a
*signal frame* by confirming the return address points to a **Trampoline**.  If
it is a trampoline it looks into the kernel saved `ucontext` and `pt_regs` find
the previous user frame.  Unwinding, can then continue as normal.

### Debugging the Issue

Now with a good background in how unwinding works we can start to debug our test
case.  We can recall our hypothesis:

> **Hypothesis**: There is a problem handling exceptions while in a syscall.
> There may be something broken with OpenRISC related to how we setup stack
> frames for syscalls that makes the unwinder fail.

With GDB we can start to debug exception handling, we can trace right to the
start of the exception handling logic by setting our breakpoint at
`_Unwind_ForcedUnwind`.

This is the stack trace we see:

```
#0  _Unwind_ForcedUnwind_Phase2 (exc=0x30caf658, context=0x30caeb6c, frames_p=0x30caea90) at ../../../libgcc/unwind.inc:192
#1  0x30303858 in _Unwind_ForcedUnwind (exc=0x30caf658, stop=0x30321dcc <unwind_stop>, stop_argument=0x30caeea4) at ../../../libgcc/unwind.inc:217
#2  0x30321fc0 in __GI___pthread_unwind (buf=<optimized out>) at unwind.c:121
#3  0x30312388 in __do_cancel () at pthreadP.h:313
#4  sigcancel_handler (sig=32, si=0x30caec98, ctx=<optimized out>) at nptl-init.c:162
#5  sigcancel_handler (sig=<optimized out>, si=0x30caec98, ctx=<optimized out>) at nptl-init.c:127
#6  <signal handler called>
#7  0x303266d0 in __futex_abstimed_wait_cancelable64 (futex_word=0x7ffffd78, expected=1, clockid=<optimized out>, abstime=0x0, private=<optimized out>)
    at ../sysdeps/nptl/futex-internal.c:66
#8  0x303210f8 in __new_sem_wait_slow64 (sem=0x7ffffd78, abstime=0x0, clockid=0) at sem_waitcommon.c:285
#9  0x00002884 in tf (arg=0x7ffffd78) at throw-pthread-sem.cc:35
#10 0x30314548 in start_thread (arg=<optimized out>) at pthread_create.c:463
#11 0x3043638c in __or1k_clone () from /lib/libc.so.6
Backtrace stopped: frame did not save the PC
(gdb)
```

In the GDB backtrack we can see it unwinds through, the signal frame, `sem_wait`
all the way to our thread routine `tf`.  It appears everything, is working fine.
But we need to remember the backtrace we see above is from GDB's unwinder not
GCC, also it uses the `.debug_info` DWARF data, not `.eh_frame`.

To really ensure the GCC unwinder is working as expected we need to debug it
walking the stack.  Debugging when we unwind a signal frame can be done by
placing a breakpoint on `or1k_fallback_frame_state`.

Debugging this code as well shows it works correctly.

```
#0  or1k_fallback_frame_state (context=<optimized out>, context=<optimized out>, fs=<optimized out>) at ../../../libgcc/unwind-dw2.c:1271
#1  uw_frame_state_for (context=0x30caeb6c, fs=0x30cae914) at ../../../libgcc/unwind-dw2.c:1271
#2  0x30303200 in _Unwind_ForcedUnwind_Phase2 (exc=0x30caf658, context=0x30caeb6c, frames_p=0x30caea90) at ../../../libgcc/unwind.inc:162
#3  0x30303858 in _Unwind_ForcedUnwind (exc=0x30caf658, stop=0x30321dcc <unwind_stop>, stop_argument=0x30caeea4) at ../../../libgcc/unwind.inc:217
#4  0x30321fc0 in __GI___pthread_unwind (buf=<optimized out>) at unwind.c:121
#5  0x30312388 in __do_cancel () at pthreadP.h:313
#6  sigcancel_handler (sig=32, si=0x30caec98, ctx=<optimized out>) at nptl-init.c:162
#7  sigcancel_handler (sig=<optimized out>, si=0x30caec98, ctx=<optimized out>) at nptl-init.c:127
#8  <signal handler called>
#9  0x303266d0 in __futex_abstimed_wait_cancelable64 (futex_word=0x7ffffd78,  expected=1, clockid=<optimized out>, abstime=0x0, private=<optimized out>) at ../sysdeps/nptl/futex-internal.c:66
#10 0x303210f8 in __new_sem_wait_slow64 (sem=0x7ffffd78, abstime=0x0, clockid=0) at sem_waitcommon.c:285
#11 0x00002884 in tf (arg=0x7ffffd78) at throw-pthread-sem.cc:35
```

Debugging when the unwinding stops can be done by setting a breakpoint
on the `unwind_stop` function.

When debugging I was able to see that the unwinder failed when looking for
the `__futex_abstimed_wait_cancelable64` frame.  So, this is not an issue
with unwinding signal frames.

### A second Hypothosis

Debugging showed that the uwinder is working correctly, and it can properly
unwind through our signal frames.  However, the unwinder is bailing out early
before it gets to the `tf` frame which has the catch block we need to execute.

> **Hypothesis 2**: There is something wrong finding DWARF info for `__futex_abstimed_wait_cancelable64`.

Looking at `libpthread.so` with `readelf` this function was missing completely from the `.eh_frame`
metadata.  Now we found something.

What creates the `.eh_frame` anyway?  GCC or Binutils (Assembler). If we run GCC
with the `-S` argument we can see GCC will output inline `.cfi` directives.
These `.cfi` annotations are what gets compiled to the to `.eh_frame`.  GCC
creates the `.cfi` directives and the Assembler puts them into the `.eh_frame`
section.

An example of `gcc -S`:

```c
         .file   "unwind.c"
        .section        .text
        .align 4
        .type   unwind_stop, @function
unwind_stop:
.LFB83:
        .cfi_startproc
        l.addi  r1, r1, -28
        .cfi_def_cfa_offset 28
        l.sw    0(r1), r16
        l.sw    4(r1), r18
        l.sw    8(r1), r20
        l.sw    12(r1), r22
        l.sw    16(r1), r24
        l.sw    20(r1), r26
        l.sw    24(r1), r9
        .cfi_offset 16, -28
        .cfi_offset 18, -24
        .cfi_offset 20, -20
        .cfi_offset 22, -16
        .cfi_offset 24, -12
        .cfi_offset 26, -8
        .cfi_offset 9, -4
        l.or    r24, r8, r8
        l.or    r22, r10, r10
        l.lwz   r18, -1172(r10)
        l.lwz   r20, -692(r10)
        l.lwz   r17, -688(r10)
        l.add   r20, r20, r17
        l.andi  r16, r4, 16
        l.sfnei r16, 0
```

When looking at the glibc build I noticed the `.eh_frame` data for
`__futex_abstimed_wait_cancelable64` is missing from futex-internal.o. The one
where unwinding is failing we find it was completely mising `.cfi` directives.
Why is GCC not generating `.cfi` directives for this file?


```c
        .file   "futex-internal.c"
        .section        .text
        .section        .rodata.str1.1,"aMS",@progbits,1
.LC0:
        .string "The futex facility returned an unexpected error code.\n"
        .section        .text
        .align 4
        .global __futex_abstimed_wait_cancelable64
        .type   __futex_abstimed_wait_cancelable64, @function
__futex_abstimed_wait_cancelable64:
        l.addi  r1, r1, -20
        l.sw    0(r1), r16
        l.sw    4(r1), r18
        l.sw    8(r1), r20
        l.sw    12(r1), r22
        l.sw    16(r1), r9
        l.or    r22, r3, r3
        l.or    r20, r4, r4
        l.or    r16, r6, r6
        l.sfnei r6, 0
        l.ori   r17, r0, 1
        l.cmov  r17, r17, r0
        l.sfeqi r17, 0
        l.bnf   .L14
         l.nop
```

Looking closer at the build line of these 2 files I see the build of `futex-internal.c`
is missing `-fexceptions`.

This flag is needed to enable the `eh_frame` section, which is what powers C++
exceptions, the flag is needed when we are building C code which needs to
support C++ exceptions.

So why is it not enabled?  Is this a problem with the GLIBC build?

Looking at GLIBC the `nptl/Makefile` set's `-fexceptions` explicitly for each
c file that needs it.  For example:

```
# The following are cancellation points.  Some of the functions can
# block and therefore temporarily enable asynchronous cancellation.
# Those must be compiled asynchronous unwind tables.
CFLAGS-pthread_testcancel.c += -fexceptions
CFLAGS-pthread_join.c += -fexceptions -fasynchronous-unwind-tables
CFLAGS-pthread_timedjoin.c += -fexceptions -fasynchronous-unwind-tables
CFLAGS-pthread_clockjoin.c += -fexceptions -fasynchronous-unwind-tables
CFLAGS-pthread_once.c += $(uses-callbacks) -fexceptions \
                        -fasynchronous-unwind-tables
CFLAGS-pthread_cond_wait.c += -fexceptions -fasynchronous-unwind-tables
CFLAGS-sem_wait.c += -fexceptions -fasynchronous-unwind-tables
CFLAGS-sem_timedwait.c += -fexceptions -fasynchronous-unwind-tables
CFLAGS-sem_clockwait.c = -fexceptions -fasynchronous-unwind-tables
```

It is missing such a line for `futex-internal.c`.  The following patch and a
libpthread rebuild fixes the issue!


```
--- a/nptl/Makefile
+++ b/nptl/Makefile
@@ -220,6 +220,7 @@ CFLAGS-pthread_cond_wait.c += -fexceptions -fasynchronous-unwind-tables
 CFLAGS-sem_wait.c += -fexceptions -fasynchronous-unwind-tables
 CFLAGS-sem_timedwait.c += -fexceptions -fasynchronous-unwind-tables
 CFLAGS-sem_clockwait.c = -fexceptions -fasynchronous-unwind-tables
+CFLAGS-futex-internal.c += -fexceptions -fasynchronous-unwind-tables

 # These are the function wrappers we have to duplicate here.
 CFLAGS-fcntl.c += -fexceptions -fasynchronous-unwind-tables
```

I [submitted this patch](http://sourceware-org.1504.n7.nabble.com/PATCH-nptl-Fix-issue-unwinding-through-sem-wait-futex-tt653730.html#a653833)
to GLIBC but it turns out it was already [fixed upstream](https://sourceware.org/git/?p=glibc.git;a=commit;h=a04689ee7a2600a1466354096123c57ccd1e1dc7) a few weeks before.

## Summary

I hope the investigation into debugging this C++ exception test case proved interesting.
We can learn a lot about the deep internals of our tools when we have to fix bugs in them.
Like most illusive bugs, in the end this was a trivial fix but required some
key background knowledge.

## Additional Reading
 - [The IA64 Undwinder ABI](https://itanium-cxx-abi.github.io/cxx-abi/) - The core unwinder API's
 - [Reliable DWARF Unwinding](https://fzn.fr/projects/frdwarf/dwarf-oopsla19-slides.pdf) - a Good presentation
 - [Exception Frames](https://refspecs.linuxfoundation.org/LSB_3.0.0/LSB-PDA/LSB-PDA/ehframechpt.html) - DWARF documentation


