---
title: OpenRISC GCC Status Update
layout: post
date: 2018-05-16
categories: [ software, embedded, openrisc ]
---

*News flash, the OpenRISC GCC port now can run "Hello World"*

After about 4 months of development on the [OpenRISC GCC port rewrite](/software/embedded/openrisc/2018/02/03/openrisc_gcc_rewrite.html)
I have hit my first major milestone, the "Hello World" program is working.  Over those
4 months I spent about 2 months working on my from scratch dummy `SMH` port
then 2 months to get the [OpenRISC port](https://github.com/stffrdhrn/gcc/tree/or1k-port)
to this stage.

## Next Steps

There are still many todo items before this will be ready for general use, including:

- Milestone 2 items
  - Investigate and Fix test suite failures, see below
  - Write OpenRISC specific test cases
  - Ensure all memory layout and calling conventions are within spec
  - Ensure sign extending, overflow, and carry flag arithmetic is correct
  - Fix issues with GDB debugging `target remote` is working OK, `target sim` is having issues.
  - Implement stubbed todo items, see below
  - Support for C++, I haven't even tried to compile it yet

- Milestone 3 items
  - Support for position independent code (PIC)
  - Support for thread local storage (TLS)
  - Support for floating point instructions (FPU)
  - Support for Atomic Builtins

Somewhere between milestone 2 and 3 I will start to work on getting the port
reviewed on the GCC and OpenRISC mailing lists.  If anyone wants to review right
now please feel free to send feedback.

## Test Suite Results

Running the gcc testsuite right now shows the following results.  Many of these
look to be related to `internal compiler errors`.

```
                === gcc Summary ===

# of expected passes            84301
# of unexpected failures        5096
# of unexpected successes       3
# of expected failures          211
# of unresolved testcases       2821
# of unsupported tests          2630
/home/shorne/work/gnu-toolchain/build-gcc/gcc/xgcc  version 9.0.0 20180426 (experimental) (GCC)
```

## Stubbed TODO Items

Some of the stubbed todo items include:

### Trampoline Handling

In `gcc/config/or1k/or1k.h` implement trampoline hooks for nested functions.

```
#define TRAMPOLINE_SIZE 12
#define TRAMPOLINE_ALIGNMENT (abort (), 0)
```

### Profiler Hooks

In `gcc/config/or1k/or1k.h` implement profiling hooks.

```
#define FUNCTION_PROFILER(FILE,LABELNO) (abort (), 0)
```

### Exception Handler Hooks

In `gcc/config/or1k/or1k.c` ensure what I am doing is right, on other targets
they copy the address onto the stack before returning.

```
/* TODO, do we need to just set to r9? or should we put it to where r9
   is stored on the stack?  */
void
or1k_expand_eh_return (rtx eh_addr)
{
  emit_move_insn (gen_rtx_REG (Pmode, LR_REGNUM), eh_addr);
}
```
