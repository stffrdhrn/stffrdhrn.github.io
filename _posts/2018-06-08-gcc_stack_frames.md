---
title: GCC Stack Frames
layout: post
date: 2018-06-08 16:37
categories: [ software, embedded, openrisc ]
---

*What I learned from doing the [OpenRISC GCC port]({% post_url 2018-02-03-openrisc_gcc_rewrite %}), defining the stack frame*

This is a continuation on my notes of things I learned while working on the
OpenRISC GCC backend port.  The stack frame layout is very important to get
right when implementing an architecture's [calling conventions](https://en.wikipedia.org/wiki/Calling_convention).
If not we may have a compiler that works fine with code it compiles but cannot
interoperate with libraries produced by another compiler.

For me I figured this would be most difficult as I am horrible with [off by
one](https://en.wikipedia.org/wiki/Off-by-one_error) bugs.  However, after
learning the whole picture I was able to get it working.

In this post we will go over the two main stack frame concepts:
 - Registers - The GCC internal and hard register numbers pointing into the stack
 - Stack Layout - How memory layout of the stack is defined

![Or1k Stack Frame](/content/2018/stackframe.png)

## Registers

Stack registers are cpu registers dedicated to point to different locations in
the stack.  The content of these registers is updated during function epilogue
and prologue.  In the above diagram we can see the pointed out as `AP`, `HFP`,
`FP` and `SP`.

### Virtual Registers

GCC's first glimpse of the stack.

These are created during the `expand` and eliminated during `vreg` pass.
By looking at these we cat understand the whole picture: Offsets, outgoing
arguments, incoming arguments etc.

The virtual registers are GCC's canonical view of the stack frame.  During the `vregs`
pass they will be replaced with architecture specific registers. See details on
this in my discussion on [GCC important passes](/software/embedded/openrisc/2018/06/03/gcc_passes.html).

Macro|GCC|OpenRISC
---|---|---
`VIRTUAL_INCOMING_ARGS_REGNUM`|Points to incoming arguments. `ARG_POINTER_REGNUM` + `FIRST_PARM_OFFSET`.|default
`VIRTUAL_STACK_VARS_REGNUM`|Points to local variables. `FRAME_POINTER_REGNUM` + `TARGET_STARTING_FRAME_OFFSET`.|default
`VIRTUAL_STACK_DYNAMIC_REGNUM`|`STACK_POINTER_REGNUM` + `STACK_DYNAMIC_OFFSET`.|default
`VIRTUAL_OUTGOING_ARGS_REGNUM`|Points to outgoing arguments. `STACK_POINTER_REGNUM` + `STACK_POINTER_OFFSET`.|default

### Real Registers (Sometimes)

The stack pointer will pretty much always be a real register that shows up in the final
assembly.  Other registers will be like virtuals and eliminated during some pass.

Macro|GCC|OpenRISC
---|---|---
`STACK_POINTER_REGNUM`|The hard stack pointer register, not defined where it should point|Points to the last data on the current stack frame.  i.e. 0(r1) points next function arg[0]
`FRAME_POINTER_REGNUM` (FP)|Points to automatic/local variable storage|Points to the first local variable. i.e. 0(FP) points to local variable[0].
`HARD_FRAME_POINTER_REGNUM`|The hard frame pointer, not defined where it should point|Points to the same location as the previous functions SP.  i.e. 0(r2) points to current function arg[0]
`ARG_POINTER_REGNUM`|Points to current function incoming arguments |For OpenRISC this is the same as `HARD_FRAME_POINTER_REGNUM`.

## Stack Layout

Stack layout defines how the stack frame is placed in memory.

### Eliminations

Eliminations provide the rules for which registers can be eliminated by
replacing them with another register and a calculated offset.  The offset is
calculated by looking at data collected by the `TARGET_COMPUTE_FRAME_LAYOUT`
macro function.

On OpenRISC we have defined these below.  We allow the frame pointer and
argument pointer to be eliminated.  They will be replaced with either the stack
pointer register or the hard frame pointer. In OpenRISC there is no argument
pointer so it will always need to be eliminated.  Also, the frame pointer is a
placeholder, when elimination is done it will be eliminated.

*Note* GCC knows that at some optimization levels the hard frame pointer will be
omitted.  In these cases `HARD_FRAME_POINTER_REGNUM` will not selected as the
elimination target register.  We don't need to define any hard frame pointer
eliminations.


Macro|GCC|OpenRISC
---|---|---
`ELIMINABLE_REGS`|Sets of registers from, to which we can eliminate by calculating the difference between them.|We eliminate Argument Pointer and Frame Pointer.
`INITIAL_ELIMINATION_OFFSET`|Function to compute the difference between eliminable registers.|See implementation below

#### Example

`or1k.h`

```
#define ELIMINABLE_REGS					\
{ { FRAME_POINTER_REGNUM, STACK_POINTER_REGNUM },	\
  { FRAME_POINTER_REGNUM, HARD_FRAME_POINTER_REGNUM },	\
  { ARG_POINTER_REGNUM,	 STACK_POINTER_REGNUM },	\
  { ARG_POINTER_REGNUM,   HARD_FRAME_POINTER_REGNUM } }

#define INITIAL_ELIMINATION_OFFSET(FROM, TO, OFFSET) \
  do {							\
    (OFFSET) = or1k_initial_elimination_offset ((FROM), (TO)); \
  } while (0)
```

`or1k.c`

{% highlight c %}
HOST_WIDE_INT
or1k_initial_elimination_offset (int from, int to)
{
  HOST_WIDE_INT offset;

  /* Set OFFSET to the offset from the stack pointer.  */
  switch (from)
    {
    /* Incoming args are all the way up at the previous frame.  */
    case ARG_POINTER_REGNUM:
      offset = cfun->machine->total_size;
      break;

    /* Local args grow downward from the saved registers.  */
    case FRAME_POINTER_REGNUM:
      offset = cfun->machine->args_size + cfun->machine->local_vars_size;
      break;

    default:
      gcc_unreachable ();
    }

  if (to == HARD_FRAME_POINTER_REGNUM)
    offset -= cfun->machine->total_size;

  return offset;
}
{% endhighlight %}

### Stack Section Growth

Some sections of the stack frame may contain multiple variables, for example we
may have multiple outgoing arguments or local variables.  The order in which
these are stored in memory is defined by these macros.

*Note* On OpenRISC the local variables definition changed during implementation
from upwards to downwards.  These are local only to the current function so does
not impact calling conventions.

Macro|GCC|OpenRISC
---|---|---
`STACK_GROWS_DOWNWARD`|Define true if new stack frames decrease towards memory address 0x0.|1
`FRAME_GROWS_DOWNWARD`|Define true if increasing local variables are at negative offset from FP.|1
`ARGS_GROW_DOWNWARD`|Define true if increasing function arguments are at negative offset from AP for incoming args and SP for outgoing args.|0 (default)

### Stack Section Offsets

Offsets may be required if an architecture has extra offsets between the
different register pointers and the actual variable data.  In OpenRISC we have
no such offsets.

Macro|GCC|OpenRISC
---|---|---
`STACK_POINTER_OFFSET`|See `VIRTUAL_OUTGOING_ARGS_REGNUM`|0
`FIRST_PARM_OFFSET`|See `VIRTUAL_INCOMING_ARGS_REGNUM`|0
`STACK_DYNAMIC_OFFSET`|See `VIRTUAL_STACK_DYNAMIC_REGNUM`|0
`TARGET_STARTING_FRAME_OFFSET`|See `VIRTUAL_OUTGOING_ARGS_REGNUM`|0

### Outgoing Arguments

When a function calls another function sometimes the arguments to that function
will need to be stored to the stack before making the function call.  For
OpenRISC this is when we have more arguments than fit in argument registers or
when we have [variadic arguments](https://en.wikipedia.org/wiki/Variadic_function).  The outgoing
arguments for all child functions need to be accounted for and the space will be
allocated on the stack.

On some architectures outgoing arguments are pushed onto and popped off the
stack.  For OpenRISC we do not do this we simply, allocate the required memory in
the prologue.

Macro|GCC|OpenRISC
---|---|---
`ACCUMULATE_OUTGOING_ARGS`|If defined, don't push args just store in `crtl->outgoing_args_size`.  Our prologue should allocate this space relative to the SP (as per `ARGS_GROW_DOWNWARD`).|1
`CUMULATIVE_ARGS`|A C type used for tracking args in the `TARGET_FUNCTION_ARG_*` macros.|`int`
`INIT_CUMULATIVE_ARGS`|Initializes a newly created `CUMULALTIVE_ARGS` type.|Sets the int variable to 0
`TARGET_FUNCTION_ARG`|Return a reg RTX or Zero to indicate when to start to pass outgoing args on the stack.|See implementation
`FUNCTION_ARG_REGNO_P`|Returns true of the given register number is used for passing outgoing function arguments.|`r3` to `r8` are OK for arguments
`TARGET_FUNCTION_ARG_ADVANCE`|This is called during iterating through outgoing function args to account for the next function arg size.|See implementation

## Further Reading

These references were very helpful in getting our calling conventions right:

 - [Stack frame layout on x86-64](https://eli.thegreenplace.net/2011/09/06/stack-frame-layout-on-x86-64/) - some great diagrams and examples
 - [14.8 Registers and Memory](https://gcc.gnu.org/onlinedocs/gccint/Regs-and-Memory.html) - gcc internals, stack layout virtual registers
 - [18.9.5 Eliminating Frame Pointer and Arg Pointer](https://gcc.gnu.org/onlinedocs/gccint/Elimination.html#Elimination) - gcc internals
 - [18.9.6 Passing Function Arguments on the Stack](https://gcc.gnu.org/onlinedocs/gccint/Stack-Arguments.html#Stack-Arguments) - gcc internals
 - [18.9.7 Passing Arguments in Registers](https://gcc.gnu.org/onlinedocs/gccint/Register-Arguments.html#Register-Arguments) - gcc internals
 - [OpenRISC Frame Handling](https://www.embecosm.com/appnotes/ean3/html/ch04s02s05.html) - embecosm, but I think the diagram has link register and previous FP in the wrong place
