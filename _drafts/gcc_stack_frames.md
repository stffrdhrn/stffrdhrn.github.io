---
title: GCC Stack Frames
layout: post
date: 2018-05-21 22:37
categories: [ software, embedded, openrisc ]
---

## Registers

### Virtual Registers

These are created during the `expand` and `vreg` passes, but will be eliminated
right away.  But looking at these you will understand the whole picture: Offsets
outgoing args, incoming args etc.

Macro|GCC|OpenRISC
---|---|---
VIRTUAL_INCOMING_ARGS_REGNUM|Points to incoming arguments. ARG_POINTER_REGNUM + FIRST_PARM_OFFSET.|default
VIRTUAL_STACK_VARS_REGNUM|Points to local variables. FRAME_POINTER_REGNUM + TARGET_STARTING_FRAME_OFFSET.|default
VIRTUAL_STACK_DYNAMIC_REGNUM|STACK_POINTER_REGNUM + STACK_DYNAMIC_OFFSET.|default
VIRTUAL_OUTGOING_ARGS_REGNUM|Points to outoing arguments. STACK_POINTER_REGNUM + STACK_POINTER_OFFSET.|default

### Real Registers (Sometimes)

Stack pointer will pretty much always be a real register that shows up in the final
assembly.  Other registers will be like virtuals and eliminated.

Macro|GCC|OpenRISC
---|---|---
STACK_POINTER_REGNUM|The hard stack pointer register, not defined where it should point|Points to the last data on the current stack frame.  i.e. 0(r1) points next function arg[0]
FRAME_POINTER_REGNUM (FP)|Points to automatic/local variable storage|Points to the first local variable. i.e. 0(FP) points to local variable[0].
HARD_FRAME_POINTER_REGNUM|The hard frame pointer, not defined where it should point|Points to the same location as the previous functions SP.  i.e. 0(r2) points to current function arg[0]
ARG_POINTER_REGNUM|Points to current function incoming arguments |For openrisc this is the same as HARD_FRAME_POINTER_REGNUM.

## Stack Layout

### Eliminations

Macro|GCC|OpenRISC
---|---|---
ELIMINABLE_REGS|Sets of registers from, to which we can eliminate by calculating the difference between them.|We eliminate Argument Pointer and Frame Pointer.
INITIAL_ELIMINATION_OFFSET|Function to compute the difference between eliminable registers.|

### Stack Section Growth

Macro|GCC|OpenRISC
---|---|---
STACK_GROWS_DOWNWARD|Define true if new stack frames decrease towards 0x0.|TRUE
FRAME_GROWS_DOWNWARD|Define true if increasing local variables are at negative offset from FP.|FALSE
ARGS_GROW_DOWNWARD|Define true if increasing function arguments are at negative offset from AP for incoming args and SP for outgoing args.|FALSE

### Stack Section Offsets

Macro|GCC|OpenRISC
---|---|---
STACK_POINTER_OFFSET|See VIRTUAL_OUTGOING_ARGS_REGNUM|0
FIRST_PARM_OFFSET|See VIRTUAL_INCOMING_ARGS_REGNUM|0
STACK_DYNAMIC_OFFSET|See VIRTUAL_STACK_DYNAMIC_REGNUM|
TARGET_STARTING_FRAME_OFFSET|See VIRTUAL_OUTGOING_ARGS_REGNUM|0

### Outgoing args

Macro|GCC|OpenRISC
---|---|---
ACCUMULATE_OUTGOING_ARGS|If defined, dont push args just store in crtl->outgoing_args_size.  Your prologue should allocate this space relative to the SP (as per ARGS_GROW_DOWNWARD).|TRUE
CUMULATIVE_ARGS|A C type used for tracking args in the TARGET_FUNCTION_ARG_* macros.|int
INIT_CUMULATIVE_ARGS|Initializes a newly created CUMULALTIVE_ARGS type.|Sets the int variable to 0
TARGET_FUNCTION_ARG|Return a reg RTX or Zero to indicate to pass outgoing args on the stack.|
FUNCTION_ARG_REGNO_P|Returns true of the given register number is used for passing outgoing function arguments.|
TARGET_FUNCTION_ARG_ADVANCE|This is called during iterating through outgoing function args to account for the next function arg size.|


