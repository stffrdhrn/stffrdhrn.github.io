---
title: OpenRISC FPU Port - Updating Linux, Compilers and Debuggers
layout: post
date: 2023-08-24 06:49
categories: [ hardware, embedded, openrisc ]
---

In this series we introduce the [OpenRISC](https://openrisc.io/)
[glibc](https://sourceware.org/glibc/) [FPU](https://en.wikipedia.org/wiki/Floating-point_unit)
port and the effort required to get user space FPU support into OpenRISC Linux.
Adding FPU support to [user space](https://en.wikipedia.org/wiki/User_space_and_kernel_space)
applications is a full stack project covering:

 - [Architecture Specification]({% post_url 2023-04-25-or1k-fpu-port %})
 - [Simulators and CPU implementations]({% post_url 2023-08-22-or1k-fpu-hw %})
 - Linux Kernel support
 - GCC Instructions and Soft FPU
 - Binutils/GDB Debugging Support
 - glibc support

Have a look at previous articles if you need to catch up.  In this article we will cover
updates done to the Linux kernel, GCC (done long ago) and GDB for debugging.

# Porting Linux to an FPU

Supporting hardware floating point operations in Linux user space applications
means adding the ability for the Linux kernel to store and restore FPU state
upon context switches.  This allows multiple programs to use the FPU at the same
time as if each program has it's own floating point hardware.  The kernel allows
programs to multiplex usage of the FPU transparently.  This is similar to how
the kernel allows user programs to share other hardware like the **CPU** and
**Memory**.

On OpenRISC this requires to only add one addition register, the floating point
control and status register (`FPCSR`) to to context switches.  The `FPCSR`
contains status bits pertaining to [rounding mode and exceptions](https://docs.oracle.com/cd/E19957-01/806-3568/ncg_goldberg.html).

We will cover three places where Linux needs to store FPU state:

 - Context Switching
 - Exception Context (Signal Frames)
 - Register Sets

## Context Switching

In order for the kernel to be able to support FPU usage in user space programs
it needs to be able to save and restore the FPU state during context switches.
Let's look at the different kinds of context switches that happen in the Linux
Kernel to understand where FPU state needs to be stored and restored.

For our discussion purposes we will define a context switch as being
when one CPU state (context) is saved from CPU hardware (registers and program
counter) to memory and then another CPU state is loaded form memory to CPU
hardware.  This can happen in a few ways.

 1. When handling exceptions such as interrupts
 3. When the scheduler wants to swap out one process for another.

Furthermore, exceptions may be categorized into one of two cases: Interrupts and
[System calls](https://en.wikipedia.org/wiki/System_call).  For each of these a different amount of CPU state needs to be saved.

 1. **Interrupts** - for timers, hardware interrupts, illegal instructions etc,
    for this case the **full** context will be saved.  As the switch is not
    anticipated by the currently running program.
 2. **System calls** - for system calls the user space program knows that it is making a function
    call and therefore will save required state as per [OpenRISC calling conventions](https://openrisc.io/or1k.html#__RefHeading__504887_595890882).
    In this case the kernel needs to save only **callee** saved registers.

Below are outlines of the sequence of operations that take place when
transitioning from Interrupts and System calls into kernel code.  It highlights
at what point state is saved with the <span style="color:white;background-color:#69f;">INT</span>,
<span style="color:white;background-color:black;">kINT</span> and <span style="color:white;background-color:#c6f;">FPU</span> labels.

The `monospace` labels below correspond to the actual assembly labels in [entry.S](https://elixir.bootlin.com/linux/latest/source/arch/openrisc/kernel/entry.S),
the part of the OpenRISC Linux kernel that handles entry into kernel code.

**Interrupts (slow path)**

 1. `EXCEPTION_ENTRY` - all <span style="color:white;background-color:#69f;">INT</span> state is saved
 2. Handle *exception* in kernel code
 3. `_resume_userspace` - Check `thread_info` for work pending
 4. If work pending
    - `_work_pending` - Call [do_work_pending](https://elixir.bootlin.com/linux/latest/source/arch/openrisc/kernel/signal.c#L298)
      - Check if reschedule needed
        - If so, performs `_switch` which save/restores <span style="color:white;background-color:black;">kINT</span>
          and <span style="color:white;background-color:#c6f;">FPU</span> state
      - Check for pending signals
        - If so, performs `do_signal` which save/restores <span style="color:white;background-color:#c6f;">FPU</span> state
 5. `RESTORE_ALL` - all <span style="color:white;background-color:#69f;">INT</span> state is restored and return to user space

**System calls (fast path)**

 1. `_sys_call_handler` - callee saved registers are saved
 2. Handle *syscall* in kernel code
 3. `_syscall_check_work` - Check `thread_info` for work pending
 4. If work pending
    - Save additional <span style="color:white;background-color:#69f;">INT</span> state
    - `_work_pending` - Call [do_work_pending](https://elixir.bootlin.com/linux/latest/source/arch/openrisc/kernel/signal.c#L298)
      - Check if reschedule needed
        - If so, performs `_switch` which save/restores <span style="color:white;background-color:black;">kINT</span>
          and <span style="color:white;background-color:#c6f;">FPU</span> state
      - Check for pending signals
        - If so, performs `do_signal` which save/restores <span style="color:white;background-color:#c6f;">FPU</span> state
    - `RESTORE_ALL` - all <span style="color:white;background-color:#69f;">INT</span> state is restored and return to user space
 5. `_syscall_resume_userspace` - restore callee saved registers return to user space.

Some key points to note on the above:

 - <span style="color:white;background-color:#69f;">INT</span> denotes the interrupts program's register state.
 - <span style="color:white;background-color:black;">kINT</span> denotes the kernel's register state before/after a context switch.
 - <span style="color:white;background-color:#c6f;">FPU</span> deones FPU state
 - In both cases step **4** checks for work pending, which may cause task
   rescheduling in which case a **Context Switch** (or *task switch*) will
   be performed.
   If rescheduling is not performed then after the sequence is complete processing
   will resume where it left off.
 - Step **1**, when switching from *user mode* to *kernel mode* is called a **Mode Switch**
 - Interrupts may happen in *user mode* or *kernel mode*
 - System calls will only happen in *user mode*
 - <span style="color:white;background-color:#c6f;">FPU</span> state only needs to be saved and restored for *user mode* programs,
   because *kernel mode* programs, in general, do not use the FPU.
 - The current version of the OpenRISC port as of `v6.8` save and restores both
   <span style="color:white;background-color:#69f;">INT</span> and <span style="color:white;background-color:#c6f;">FPU</span> state
   what is shown before is a more optimized mechanism of only saving FPU state when needed.  Further optimizations could
   be still make to only save FPU state for user space, and not save/restore if it is already done.

With these principal's in mind we can now look at how the mechanics of context
switching works.

Upon a **Mode Switch** from *user mode* to *kernel mode* the process thread
stack switches from using a user space stack to the associated kernel space
stack.  The required state is stored to a stack frame in a `pt_regs` structure.

The `pt_regs` structure (originally ptrace registers) represents the CPU
*registers* and *program counter* context that needs to be saved.

Below we can see how the kernel stack and user space stack relate.

```
               kernel space

 +--------------+       +--------------+
 | Kernel Stack |       | Kernel stack |
 |     |        |       |    |         |
 |     v        |       |    |         |
 |   pt_regs' -------\  |    v         |
 |     v        |    |  |  pt_regs' --------\
 |   pt_reg''   |<+  |  |    v         |<+  |
 |              | |  |  |              | |  |
 +--------------+ |  |  +--------------+ |  |
 | thread_info  | |  |  | thread_info  | |  |
 | *task        | |  |  | *task        | |  |
 |  ksp ----------/  |  |  ksp-----------/  |
 +--------------+    |  +--------------+    |
                     |                      |
0xc0000000           |                      |
---------------------|----------------------|-----
                     |                      |
  process a          |   process b          |
 +--------------+    |  +------------+      |
 | User Stack   |    |  | User Stack |      |
 |     |        |    |  |    |       |      |
 |     V        |<---+  |    v       |<-----+
 |              |       |            |
 |              |       |            |
 | text         |       | text       |
 | heap         |       | heap       |
 +--------------+       +------------+


0x00000000
               user space
```

*process a*

In the above diagram notice how there are 2 set's of `pt_regs` for process a.
The *pt_regs'* structure represents the user space registers (<span style="color:white;background-color:#69f;">INT</span>)
that are saved during the switch from *user mode* to *kernel mode*.  Notice how
the *pt_regs'* structure has an arrow pointing the user space stack, that's the
saved stack pointer.  The second *pt_regs''* structure represents the frozen
kernel state (<span style="color:white;background-color:black;">kINT</span>)
that was saved before a task *switch* was performed.

*process b*

Also in the diagram above we can see process b has only a *pt_regs'* (<span style="color:white;background-color:#69f;">INT</span>)
structure saved on the stack and does not currently have a *pt_regs''* (<span style="color:white;background-color:black;">kINT</span>)
structure saved.  This indicates that that this process is currently running in
kernel space and is not yet frozen.

As we can see here, for OpenRISC there are two places to store state.
  - The *mode switch* context is saved to a `pt_regs` structure on the kernel
    stack represented by *pt_regs'* at this point only integer registers need to
    be saved.  This is represents the user process state.
  - The *context switch* context is stored by OpenRISC again on the stack,
    represented by *pt_regs''*.  This represents the kernel's state before a
    task switch.  All state that the kernel needs to resume later is stored.  In
    other architectures this state is not stored on the stack but to the `task`
    structure or to the `thread_info` structure.  This context may store the all
    extra registers including FPU and Vector registers.

<div class="note">
<b>Note</b> In the above diagram we can see the kernel stack and <code>thread_info</code> live in
the same memory space.  This is a source of security issues and many
architectures have moved to support <a href="https://docs.kernel.org/mm/vmalloced-kernel-stacks.html">virtually mapped kernel stacks</a>,
OpenRISC does not yet support this and it would be a good opportunity
for improvement.
</div>

The structure of the `pt_regs` used by OpenRISC is as per below:

```c
struct pt_regs {
	long gpr[32];
	long  pc;
	/* For restarting system calls:
	 * Set to syscall number for syscall exceptions,
	 * -1 for all other exceptions.
	 */
	long orig_gpr11;	/* For restarting system calls */
	long dummy;             /* Cheap alignment fix */
	long dummy2;		/* Cheap alignment fix */
};
```

The structure of `thread_struct` now used by OpenRISC to store the
user specific FPU state is as per below:

```c
struct thread_struct {
       long fpcsr;      /* Floating point control status register. */
};
```

The patches to OpenRISC added to support saving and restoring FPU state
during context switches are below:

 - 2023-04-26 [63d7f9f11e5e](https://github.com/stffrdhrn/linux/commit/63d7f9f11e5e81de2ce8f1c7a8aaed5b0288eddf) Stafford Horne   openrisc: Support storing and restoring fpu state
 - 2024-03-14 [ead6248f25e1](https://github.com/stffrdhrn/linux/commit/ead6248f25e1) Stafford Horne   openrisc: Move FPU state out of pt_regs

## Signals and Signal Frames.

Signal frames are another place that we want FPU's state, namely `FPCSR`, to be available.

When a user process receives a signal it executes a signal handler in the
process space on a stack slightly outside it's current stack.  This is setup
with [setup_rt_frame](https://elixir.bootlin.com/linux/latest/source/arch/openrisc/kernel/signal.c#L156).

As we saw above signals are received after syscalls or exceptions, during the
`do_pending_work` phase of the entry code.  This means means FPU state will need
to be saved and restored.

Again, we can look at the stack frames to paint a picture of how this works.

```
               kernel space

 +--------------+
 | Kernel Stack |
 |     |        |
 |     v        |
 |   pt_regs' --------------------------\
 |              |<+                      |
 |              | |                      |
 |              | |                      |
 +--------------+ |                      |
 | thread_info  | |                      |
 | *task        | |                      |
 |  ksp ----------/                      |
 +--------------+                        |
                                         |
0xc0000000                               |
-------------------                      |
                                         |
  process a                              |
 +--------------+                        |
 | User Stack   |                        |
 |     |        |                        |
 |     V        |                        |
 |xxxxxxxxxxxxxx|> STACK_FRAME_OVERHEAD  |
 | siginfo      |\                       |
 | ucontext     | >- sigframe            |
 | retcode[]    |/                       |
 |              |<-----------------------/
 |              |
 | text         |
 | heap         |
 +--------------+

0x00000000
               user space
```

Here we can see that when we enter a signal handler, we can get a bunch of stuff
stuffed in the stack in a `sigframe` structure.  This includes the `ucontext`,
or user context which points to the original state of the program, registers and
all.  It also includes a bit of code, `retcode`, which is a trampoline to bring us
back into the kernel after the signal handler finishes.

<div class="note">
<b>Note</b> we could also setup an alternate <a href="https://man7.org/linux/man-pages/man2/sigaltstack.2.html">signalstack</a>
to use instead of stuffing stuff onto the main user stack. The
above example is the default behaviour.
</div>

The user `pt_regs` (as we called *pt_regs'*) is updated before returning to user
space to execute the signal handler code by updating the registers as follows:

```
  sp:    stack pinter updated to point to a new user stack area below sigframe
  pc:    program counter:  sa_handler(
  r3:    argument 1:                   signo,
  r4:    argument 2:                  &siginfo
  r5:    argument 3:                  &ucontext)
  r9:    link register:    retcode[]
```

Now, when we return from the kernel to user space, user space will resume in the
signal handler, which runs within the user process context.

After the signal handler completes it will execute the `retcode`
block which is setup to call the special system call [rt_sigreturn](https://man7.org/linux/man-pages/man2/sigreturn.2.html).

<div class="note">
<b>Note</b> for OpenRISC this means the stack has to be executable. Which is
a <a href="https://en.wikipedia.org/wiki/Stack_buffer_overflow">major security vulnerability</a>.
Modern architectures do not have executable stacks and use <a href="https://man7.org/linux/man-pages/man7/vdso.7.html">vdso</a>
or is provided by libc in <code>sa_restorer</code>.
</div>

The `rt_sigreturn` system call will restore the `ucontext` registers (which
may have been updated by the signal handler) to the user `pt_regs` on the
kernel stack.  This allows us to either restore the user context before the
signal was received or return to a new context setup by the signal handler.

### A note on user space ABI compatibility for signals.

We need to to provide and restore the FPU `FPCSR` during signals via
`ucontext` but also not break user space ABI.  The ABI is important because
kernel and user space programs may be built at different times.  This means the
layout of existing fields in `ucontext` cannot change.  As we can see below by
comparing the `ucontext` definitions from Linux, glibc and musl each program
maintains their own separate header file.

In Linux we cannot add fields to `uc_sigcontext` as it would make `uc_sigmask`
unable to be read.  Fortunately we had a bit of space in `sigcontext` in the
unused `oldmask` field which we could repurpose for `FPCSR`.

The structure used by Linux to populate the signal frame is:

From: [uapi/asm-generic/ucontext.h](https://elixir.bootlin.com/linux/v6.8/source/include/uapi/asm-generic/ucontext.h#L5)

```c
struct ucontext {
        unsigned long     uc_flags;
        struct ucontext  *uc_link;
        stack_t           uc_stack;
        struct sigcontext uc_mcontext;
        sigset_t          uc_sigmask;
};
```
From: [uapi/asm/ptrace.h](https://elixir.bootlin.com/linux/v6.8/source/arch/openrisc/include/uapi/asm/ptrace.h)

```c
struct sigcontext {
        struct user_regs_struct regs;  /* needs to be first */
        union {
                unsigned long fpcsr;
                unsigned long oldmask;  /* unused */
        };
};
```

<div class="note">
<b>Note</b> In <code>sigcontext</code> originally a <code>union</code> was not used
and <a href="https://lore.kernel.org/linux-mm/ZL2V77V8xCWTKVR+@antec/T/">caused ABI breakage</a>; which was soon fixed.
</div>

From: [uapi/asm/sigcontext.h](https://elixir.bootlin.com/linux/v6.8/source/arch/openrisc/include/uapi/asm/sigcontext.h)

```c
struct user_regs_struct {
        unsigned long gpr[32];
        unsigned long pc;
        unsigned long sr;
};
```

The structure that [glibc](https://sourceware.org/git/?p=glibc.git;a=blob;f=sysdeps/unix/sysv/linux/or1k/sys/ucontext.h;h=b17e91915461b5f2095682efd174e7612d2ec119;hb=HEAD) expects is.

```c
/* Context to describe whole processor state.  */
typedef struct
  {
    unsigned long int __gprs[__NGREG];
    unsigned long int __pc;
    unsigned long int __sr;
  } mcontext_t;

/* Userlevel context.  */
typedef struct ucontext_t
  {
    unsigned long int __uc_flags;
    struct ucontext_t *uc_link;
    stack_t uc_stack;
    mcontext_t uc_mcontext;
    sigset_t uc_sigmask;
  } ucontext_t;
```

<div class="note">
<b>Note</b> This is broken, the <code>struct mcontext_t</code> in glibc is
missing the space for <code>oldmask</code>.
</div>

The structure used by [musl](https://git.musl-libc.org/cgit/musl/tree/arch/or1k/bits/signal.h) is:

```c
typedef struct sigcontext {
	struct {
		unsigned long gpr[32];
		unsigned long pc;
		unsigned long sr;
	} regs;
	unsigned long oldmask;
} mcontext_t;

typedef struct __ucontext {
	unsigned long uc_flags;
	struct __ucontext *uc_link;
	stack_t uc_stack;
	mcontext_t uc_mcontext;
	sigset_t uc_sigmask;
} ucontext_t;

```

Below were the patches the to OpenRISC kernel to add floating point state to the
signal API.  This originally caused some ABI breakage and was fixed in the second patch.

 - 2023-04-26 [27267655c531](https://github.com/stffrdhrn/linux/commit/27267655c531) Stafford Horne   openrisc: Support floating point user api
 - 2023-07-10 [dceaafd66881](https://github.com/stffrdhrn/linux/commit/dceaafd66881) Stafford Horne   openrisc: Union fpcsr and oldmask in sigcontext to unbreak userspace ABI

## Register sets

Register sets provide debuggers the ability to read and save the state of
registers in other processes.  This is done via the [ptrace](https://man7.org/linux/man-pages/man2/ptrace.2.html)
`PTRACE_GETREGSET` and `PTRACE_SETREGSET` requests.

Regsets also define what is dumped to [core dumps](https://en.wikipedia.org/wiki/Core_dump) when a process crashes.

In OpenRISC we added the ability to get and set the `FPCSR` register
with the following patches:

 - 2023-04-26 [c91b4a07655d](https://github.com/stffrdhrn/linux/commit/c91b4a07655d) Stafford Horne   openrisc: Add floating point regset  (shorne/or1k-6.4-updates, or1k-6.4-updates)
 - 2024-03-14 [14f89b18c117](https://github.com/stffrdhrn/linux/commit/14f89b18c1173fb6664bb338db850f5ad0484b93#diff-0c4ba219cbf5887111a27c6234092536a513f07927c418c14bb227a8ac85eaae) Stafford Horne   openrisc: Move FPU state out of pt_regs

# Porting GCC to an FPU

## Supporting FPU Instructions

I ported GCC to the OpenRISC FPU back in [2019](https://gcc.gnu.org/git/?p=gcc.git;a=commit;f=gcc/config/or1k/or1k.md;h=44080af98edf7d8a59a94dd803f60cf0505fba34)
, this entailed defining new instructions in the RTL machine description for
example:

```
 (define_insn "plussf3"
   [(set (match_operand:SF 0 "register_operand" "=r")
         (plus:SF (match_operand:SF 1 "register_operand" "r")
                  (match_operand:SF 2 "register_operand" "r")))]
   "TARGET_HARD_FLOAT"
   "lf.add.s\t%d0, %d1, %d2"
   [(set_attr "type" "fpu")])

 (define_insn "minussf3"
   [(set (match_operand:SF 0 "register_operand" "=r")
         (minus:SF (match_operand:SF 1 "register_operand" "r")
                   (match_operand:SF 2 "register_operand" "r")))]
   "TARGET_HARD_FLOAT"
   "lf.sub.s\t%d0, %d1, %d2"
   [(set_attr "type" "fpu")])
```

The above is a simplified example of [GCC Register Transfer Language(RTL)](https://gcc.gnu.org/onlinedocs/gccint/Arithmetic.html)
lisp expressions.  Note, the real expression actually uses mode iterators and is a bit harder to understand, hence the simplified version above.
These expressions are used for translating the GCC compiler RTL from it's [abstract syntax tree](https://en.wikipedia.org/wiki/Abstract_syntax_tree)
form to actual machine instructions.

Notice how the above expressions are in the format `(define_insn INSN_NAME RTL_PATTERN CONDITION MACHINE_INSN ...)`. If
we break it down we see:

 - `INSN_NAME` - this is a unique name given to the instruction.
 - `RTL_PATTERN` - this is a pattern we look for in the RTL tree, Notice how the lisp represents 3 registers connected by the instruction node.
 - `CONDITION` - this is used to enable the instruction, in our case we use `TARGET_HARD_FLOAT`.  This means if the GCC hardware floating point
                 option is enabled this expression will be enabled.
 - `MACHINE_INSN` - this represents the actual OpenRISC assembly instruction that will be output.

## Supporting Glibc Math

In order for glibc to properly support floating point operations GCC needs to do
a bit more than just support outputting floating point instructions.  Another
component of GCC is software floating point emulation.  When there are
operations not supported by hardware GCC needs to fallback to using software
emulation.  With way GCC and GLIBC weave software math routines and floating
point instructions we can think of the FPU as a math accelerator.  For example,
the floating point square root operation is not provided by OpenRISC hardware.

When operations like square root are not available by hardware glibc will inject
software routines to handle the operation.  The outputted square root routine
may use hardware multiply `lf.mul.s` and divide `lf.div.s` operations to
accelerate the emulation.

In order for this to work correctly the rounding mode and exception state of the
FPU and libgcc emulation need to by in sync. Notably, we had one patch to fix an
issue with exceptions not being in sync which was found when running glibc
tests.

 - 2023-03-19 [33fb1625992](https://gcc.gnu.org/git/?p=gcc.git;a=commit;h=33fb1625992ba8180b42988e714460bcab08ca0f) or1k: Do not clear existing FPU exceptions before updating

The libc [math](https://www.gnu.org/software/libc/manual/html_node/Mathematics.html) routines include:

 - Trigonometry functions - For example `float sinf (float x)`
 - Logarithm functions - For example `float logf (float x)`
 - Hyperbolic functions - For example `float ccoshf (complex float z)`

The above just names a few, but as you can imagine the floating point acceleration
provided by the `FPU` is essential for performant scientific applications.

# Adding debugging capabilities for an FPU

FPU debugging allows a user to inspect the FPU specific registers.
This includes FPU state registers and flags as well as view the floating point
values in each general purpose register.  This is not yet implemented on
OpenRISC.

This will be something others can take up.  The work required is to map
Linux FPU register sets to GDB.

# Summary

In summary adding floating point support to Linux revolved around adding one more register, the `FPCSR`,
to context switches and a few other places.

GCC fixes were needed to make sure hardware and software floating point routines could work together.

There are still improvements that can be done for the Linux port as noted above.  In the next
article we will wrap things up by showing the glibc port.

# Further Reading

- [Context Switch](https://www.linfo.org/context_switch.html) - good definition of context switches.
- [Evolution of the x86 context switch in Linux](https://www.maizure.org/projects/evolution_x86_context_switch_linux/) - A great history of the linux context switch code.
- [Notes about FPU implementation in Linux kernel](http://liujunming.top/2022/01/08/Notes-about-FPU-implementation-in-Linux-kernel/) - Next step in x86 FPU context switch optimizations, smart loading
- [Kernel Stack and User Stack](https://www.baeldung.com/linux/kernel-stack-and-user-space-stack#:~:text=User%20and%20Kernel%20Stacks,part%20of%20the%20kernel%20space.) - explaination
  of kernel and user space stacks.
- [Virtually Mapped Stacks](https://docs.kernel.org/mm/vmalloced-kernel-stacks.html) - details about relocating kernel stack for security
- [pt_regs the good the bad and the ugly](https://lpc.events/event/17/contributions/1462/) - on the history of `pt_regs`

