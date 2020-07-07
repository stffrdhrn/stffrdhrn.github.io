---
title: Relocation Implementation Details
layout: post
date: 2020-07-05 06:47
categories: [ hardware, embedded, openrisc ]
---

TODO
 - We talk about RTX, give reference to what it is.

*This is an ongoing series of posts on ELF Binary Relocations and Thread
Local Storage.  This article covers only Thread Local Storage and assumes
the reader has had a primer in ELF Relocations, if not please start with
my previous article [ELF Binaries and Relocation Entries]({% post_url 2019-11-29-relocs %})*

In the last article we covered how Thread Local Storage (TLS) works at runtime,
but how do we get there?  How does the compiler and linker create the memory
structures and code fragments described in the previous article?

In this article we will discuss how TLS is implemented.  Our outline:

 - [The Compiler](#the-compiler)
   - [GCC Legitimize Address](#gcc-legitimize-address)
   - [GCC Print Operand](#gcc-print-operand)
 - [The Assembler](#the-assembler)
 - [The Linker](#the-linker)
   - [Phase 1 - Book Keeping](#phase-1---book-keeping-check_relocs)
   - [Phase 2 - Creating Space](#phase-2---creating-space-size_dynamic_sections--_bfd_elf_create_dynamic_sections)
   - [Phase 3 - Linking](#phase-3---linking-relocate_section)
   - [Phase 4 - Finishing Up](#phase-4---finishing-up-finish_dynamic_symbol--finish_dynamic_sections)
 - [GLIBC Runtime Linker](#glibc-runtime-linker)

As before, the examples in this article can be found in my [tls-examples](https://github.com/stffrdhrn/tls-examples)
project.  Please check it out.


# The GNU toolchain

I will assume here that most people understand what a compiler and assembler
basically do.  A compiler will compile routines
written C code or something similar to assembly language.  It is then up to the
assembler to turn that assmebly code into machine code to run on a CPU.

That is a big part of what a toolchain does, and it's pretty much that simple if
we have a single file of source code.  But usually we don't have a single file,
and that is where the complexities of the linker comes in.

In this article I will cover how variables in our programs ([symbols](https://en.wikipedia.org/wiki/Symbol_table)) traverse
the toolchain from front to the back.

![GCC and Linker](/content/2019/gcc-obj-ld.png)

## The Compiler

First we start off with how relocations are created and emitted in the compiler.

As I work on the GCC compiler we will look at that, let's get started.

### GCC Legitimize Address

To start a variable or function reference refers to an address in memory.

In GCC we have have `TARGET_LEGITIMIZE_ADDRESS`, the OpenRISC implementation
being [or1k_legitimize_address](https://github.com/gcc-mirror/gcc/blob/releases/gcc-10.1.0/gcc/config/or1k/or1k.c#L841).
It takes a memory address and makes it usable in our CPU by generating code
sequences that are possible on our CPU to load that address into a register
based on variable properties.

A snippet from this function is below.  The symbol `x` represents our input symbol that we
need to make usable by our CPU.  This code uses GCC internal API's to generate RTX code
sequences.

```
static rtx
or1k_legitimize_address (rtx x, rtx /* unused */, machine_mode /* unused */)

    ...

	case TLS_MODEL_NONE:
	  t1 = can_create_pseudo_p () ? gen_reg_rtx (Pmode) : scratch;
	  if (!flag_pic)
	    {
	      emit_insn (gen_rtx_SET (t1, gen_rtx_HIGH (Pmode, x)));
	      return gen_rtx_LO_SUM (Pmode, t1, x);
	    }
	  else if (is_local)
	    {
	      crtl->uses_pic_offset_table = 1;
	      t2 = gen_sym_unspec (x, UNSPEC_GOTOFF);
	      emit_insn (gen_rtx_SET (t1, gen_rtx_HIGH (Pmode, t2)));
	      emit_insn (gen_add3_insn (t1, t1, pic_offset_table_rtx));
	      return gen_rtx_LO_SUM (Pmode, t1, copy_rtx (t2));
	    }
	  else
	    {
              ...
```

We can read the code snippet above as follows:

 - This is for the non `TLS` case as we see `TLS_MODEL_NONE`.
 - We reserve a temporary register `t1`.
 - If not using [Position-independent code](https://en.wikipedia.org/wiki/Position-independent_code) (`flag_pic`) we do:
   - Emit an instruction to put the **high** bits of `x` into out temporary register `t1`.
   - Return the sum of `t1` and the **low** bits of `x`.
 - Otherwise if the symbol is static (`is_local`) we do:
   - Mark the global state that this object file uses the `uses_pic_offset_table`.
   - We create a [Global Offset Table](https://en.wikipedia.org/wiki/Global_Offset_Table) offset variable `t2`.
   - Emit an instruction to put the **high** bits of `t2` (the got offset) into out temporary register `t1`.
   - Emit an instruction to put the sum of `t1` (**high** bits of `t2) and the GOT into `t1`.
   - Return the sum of `t1` and the **low** bits of `t1`.

You may have noticed that the "local" symbol still used the "global" offset
table (GOT).  This is because Position-idependant code requires using the
GOT to reference symbols.

An example:

```
static int x;

int *get_x_addr() {
   return &x;
}
```

Example of the *non pic case* above, we can see the address is generated using immediates.  In
this case `x` is `0009ee60` and we loaded it into register `r11` using 2 instructions.

```
        l.movhi r17, ha(x)
        l.addi  r17, r17, lo(x)
```

Example of the *local pic case* above the same code is compiled with the `-fPIC` GCC option.

```
        l.movhi r17, gotoffha(x)
        l.add   r17, r17, r19
        l.addi  r17, r17, gotofflo(x)
```

TLS and Addend cases are also handled by `or1k_legitimize_address`.

### GCC Print Operand

Once RTX is generated and [GCC passes]() run all of their optimizations the RTX
needs to be printed.  Relocations are printed by GCC macros `TARGET_PRINT_OPERAND_ADDRESS`
and `TARGET_PRINT_OPERAND`.
In OpenRISC these defined by
[or1k_print_operand_address()](https://github.com/gcc-mirror/gcc/blob/releases/gcc-10/gcc/config/or1k/or1k.c#L1139)
and
[or1k_print_operand()](https://github.com/gcc-mirror/gcc/blob/releases/gcc-10/gcc/config/or1k/or1k.c#L1193).

Let us have a look at `or1k_print_operand_address()`.

```
/* Worker for TARGET_PRINT_OPERAND_ADDRESS.
   Prints the argument ADDR, an address RTX, to the file FILE.  The output is
   formed as expected by the OpenRISC assembler.  Examples:
     RTX							      OUTPUT
     (reg:SI 3)							       0(r3)
     (plus:SI (reg:SI 3) (const_int 4))				     0x4(r3)
     (lo_sum:SI (reg:SI 3) (symbol_ref:SI ("x"))))		   lo(x)(r3)  */

static void
or1k_print_operand_address (FILE *file, machine_mode, rtx addr)
{
  rtx offset;

  switch (GET_CODE (addr))
    {
    case REG:
      fputc ('0', file);
      break;
    case ...
    case LO_SUM:
      offset = XEXP (addr, 1);
      addr = XEXP (addr, 0);
      print_reloc (file, offset, 0, RKIND_LO);
      break;
    default: ...
    }

  fprintf (file, "(%s)", reg_names[REGNO (addr)]);
}
```

The above code snippet you can be read as we explain below, but let's first
make some notes:

  - The input RTX `addr` for `TARGET_PRINT_OPERAND_ADDRESS` will usually contain
    a register and an offset typically this is used for **LOAD** and **STORE**
    operations.
  - Think of the input RTX `addr` as a node in an AST tree
  - The RTX node with code `REG` and `SYMBOL_REF` are always leaf nodes.

With that, and if we use the `or1k_print_operand_address()` comments as an example
of some RTX `addr` input we will have:

```
RTX             (reg:SI 3)          (lo_sum:SI (reg:SI 3) (symbol_ref:SI("x"))))

TREE
   (code)     (code:REG regno:3)     (code:LO_SUM)
   /    \                             /        \
  (0)   (1)               (code:REG regno:3)  (code:SYMBOL_REF "x")
```

We can now read the above snippet as:

**First** get the `CODE` of the RTX.
  - If `CODE` is `REG` (a register) than our offset can be `0`.
  - If `IS` is `LO_SUM` (an addition operation) then we need to break it down to:
     - Arg `0` is our new `addr` RTX (which we assume is a register)
     - Arg `1` is an offset (which we then print with `print_reloc()`)

**Second** print out the register name now in `addr` i.e. "r3".

The code of `or1k_print_operand()` is similar and the reader may be inclined to
read more details.  With that we can move on the the assembler.

## The Assembler

In the GNU Toolchain our assember is GAS part of [binutils](https://www.gnu.org/software/binutils/).

The code that handles relocations is found in the function
[parse_reloc()](https://github.com/bminor/binutils-gdb/blob/binutils-2_34/opcodes/or1k-asm.c#L226)
found in `opcodes/or1k-asm.c`.  This is actually part of `or1k_cgen_parse_operand()` which
is wired into our assemler generator CGEN used for parsing operands.

If we are parsing a relocation like the one from above `lo(x)` then we can
isolate the code that processes that relocation.

```
static const bfd_reloc_code_real_type or1k_imm16_relocs[][6] = {
  { BFD_RELOC_LO16,
    BFD_RELOC_OR1K_SLO16,
...
    BFD_RELOC_OR1K_TLS_LE_AHI16 },
};

static int
parse_reloc (const char **strp)
{
    const char *str = *strp;
    enum or1k_rclass cls = RCLASS_DIRECT;
    enum or1k_rtype typ;

    ...
    else if (strncasecmp (str, "lo(", 3) == 0)
      {
	str += 3;
	typ = RTYPE_LO;
      }
    ...

    *strp = str;
    return (cls << RCLASS_SHIFT) | typ;
}
```

This uses [strncasecmp](https://man7.org/linux/man-pages/man3/strncasecmp.3.html) to match
our `"lo("` string pattern.  The returned result is a relocation type and relocation class
which are use to lookup the relocation `BFD_RELOC_LO16` in the `or1k_imm16_relocs[][]` table
which is indexed by *relocation class* and *relocation class*.

The assembler will encode that into the ELF binary.

## The Linker

In the GNU Toolchain our assember is the GNU linker LD, also part of the *binutils* project.

The GNU linker uses the framework [BFD](https://sourceware.org/binutils/docs/bfd/)
or [Binary File Descriptor](https://en.wikipedia.org/wiki/Binary_File_Descriptor_library)
which is a beast.  It is not only used in the linker but also used in GDB, the
GNU Simulator and the objdump tool.

The usage of BFD in the GNU Linker can be thought of in phases.

htab - stores arch specific details
local_tls_type - store tls_type if no h
h - store detail for each symbol, how many got entries required
   got.offset where in the got the value is
```
#define elf_backend_relocate_sectio nor1k_elf_relocate_section
  - For symbol, write got section, write rela if dynamic
     - Write entry to GOT only once per symbol
  - run or1k_final_link_relocate
     - Write actual value to text

#define elf_backend_create_dynamic_sections
#define elf_backend_finish_dynamic_sections
#define elf_backend_size_dynamic_sections

or1k_elf_check_relocs - loop through relocations and do book keeping
  -
```

These are notes on the bfd API, see more:

- [bfdint](http://cahirwpz.users.sourceforge.net/binutils-2.26/bfd-internal.html/index.html#SEC_Contents)
- [ldint](http://home.elka.pw.edu.pl/~macewicz/dokumentacja/gnu/ld/ldint_2.html)

### Phase 1 - Book Keeping (check_relocs)

The `or1k_elf_check_relocs` function is called during the first phase to
validate relocations, returns FALSE if there are issues.  It also does
some book keeping.

  - abfd   - The current elf object file we are working on
  - sec    - The current elf section we are working on
  - info   - the bfd API
  - relocs - The relocations from the current section

```
static bfd_boolean
or1k_elf_check_relocs (bfd *abfd,
                       struct bfd_link_info *info,
                       asection *sec,
                       const Elf_Internal_Rela *relocs)

#define elf_backend_check_relocs        or1k_elf_check_relocs
```

### Phase 2 - creating space (size_dynamic_sections + _bfd_elf_create_dynamic_sections)

The `or1k_elf_size_dynamic_sections` function iterates over all object
files to calculate the size of stuff, it uses:

 - info - the API to bfd provides access to everything, this function uses:
      - `or1k_elf_hash_table (info)` - called `htab` a reference to `elf_link_hash_table` which has sections.
      - `htab->root.splt` - the plt section
      - `htab->root.sgot` -  the got section
      - `htab->root.srelgot` - the relgot section (relocations against the got)
      - `htab->root.sgotplt` - the gotplt section
      - `htab->root.dynobj` - a special bfd to which sections are created (created in `or1k_elf_check_relocs`)
      - `root.dynamic_sections_created` - true if sections like `.interp` have been created by the linker
      - `info->input_bfds` - loop over all bfds (elf objects)

Settig up the sizes of the `.got` section (global offset table) and `.plt` section (procedure link table) is done by iterating through all symbols with the `allocate_dynrelocs` interator.

Sub function of `or1k_elf_size_dynamic_sections` `allocate_dynrelocs` is used to additionaly set size of `.gotplt` which sometime later gets combinted with the `.got` somehow.  The `allocate_dynrelocs` is a visitor function that is used when
iterating over each `elf_link_hash_entry` which represents a symbol.

*pseudocode*
```
  allocate_dynrelocs(h) {
     if (h->plt.refcount > 0) {
        .gotplt->size ++;
        .relocations->size ++;
     }
     if (h->got.refcount > 0) {
        .got->size ++;
        .relocations->size ++;
     }

     do something with h->dyn_relocs which I don't understand
     but in the end it doe sreloc->size ++
  }
```

The sub function of `allocate_dynrelocs` `or1k_set_got_and_rela_sizes` is used to increment `.got` and `.rela` section sizes per tls symbols.

```
static bfd_boolean
or1k_elf_size_dynamic_sections (bfd *output_bfd ATTRIBUTE_UNUSED,
                                struct bfd_link_info *info)

#define elf_backend_size_dynamic_sections       or1k_elf_size_dynamic_sections
#define elf_backend_create_dynamic_sections     _bfd_elf_create_dynamic_sections
```

### Phase 3 - linking (relocate_section)

For each input section in an input bfd (`.o` file) figure out where they will exist in the output bfd.

Fill in relocation placeholders in `.text` sections.  Fill out data in `.got` and `.rela` sections.

```
static bfd_boolean
or1k_elf_relocate_section (bfd *output_bfd,
                           struct bfd_link_info *info,
                           bfd *input_bfd,
                           asection *input_section,
                           bfd_byte *contents,
                           Elf_Internal_Rela *relocs,
                           Elf_Internal_Sym *local_syms,
                           asection **local_sections)

#define elf_backend_relocate_section    or1k_elf_relocate_section
```

### Phase 4 - finishing up (finish_dynamic_symbol + finish_dynamic_sections)

Write to `.plt`

```
static bfd_boolean
or1k_elf_finish_dynamic_sections (bfd *output_bfd,
                                  struct bfd_link_info *info)

static bfd_boolean
or1k_elf_finish_dynamic_symbol (bfd *output_bfd,
                                struct bfd_link_info *info,
                                struct elf_link_hash_entry *h,
                                Elf_Internal_Sym *sym)

#define elf_backend_finish_dynamic_sections     or1k_elf_finish_dynamic_sections
#define elf_backend_finish_dynamic_symbol       or1k_elf_finish_dynamic_symbol
```

### Insane things inbetween

These are important but I cant see why they need to be specific to openrisc
or anyone writing a new port.

```
#define elf_backend_copy_indirect_symbol        or1k_elf_copy_indirect_symbol
#define elf_backend_adjust_dynamic_symbol       or1k_elf_adjust_dynamic_symbol
```

## Glibc Runtime Linker

## Relaxation

As some TLS access methods are more efficient than others we would like to choose
the best method for each variable access.  However, we don't
always know where a variable will come from until link time.

One type of relaxation performed by the linker is GD to IE relaxation.  During compile
time GD relocation may be choosen for `extern` variables.  However, during link time
the variable may be in the same module i.e. not a shared object which would require
GD access.

If relaxation can be done.
Relaxation will rewrite the GD access code in the .text section of the binary and
convert it to more efficient IE access.


## Further Reading
- [GCC Passes](% post_url 2018-06-03-gcc_passes %}) - My blog entry on GCC passes
- Bottums Up - http://bottomupcs.sourceforge.net/csbu/x3735.htm
- GOT and PLT - https://www.technovelty.org/linux/plt-and-got-the-key-to-code-sharing-and-dynamic-libraries.html
- Android - https://android.googlesource.com/platform/bionic/+/HEAD/docs/elf-tls.md
- Oracle - https://docs.oracle.com/cd/E19683-01/817-3677/chapter8-20/index.html
- Drepper - https://www.akkadia.org/drepper/tls.pdf
- Deep Dive - https://chao-tic.github.io/blog/2018/12/25/tls
