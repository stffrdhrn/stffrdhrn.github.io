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

In the GNU Toolchain our object linker is the GNU linker LD, also part of the
*binutils* project.

The GNU linker uses the framework
[BFD](https://sourceware.org/binutils/docs/bfd/) or [Binary File
Descriptor](https://en.wikipedia.org/wiki/Binary_File_Descriptor_library) which
is a beast.  It is not only used in the linker but also used in GDB, the GNU
Simulator and the objdump tool.

What makes this possible is a rather complex API.

### BFD Linker API

The BFD api is a generic binary file access API.  It has been designed to support multiple
file formats and architectures via an object oriented, polymorphic API.  It supports file formats
including [a.out](https://en.wikipedia.org/wiki/A.out),
[COFF](https://en.wikipedia.org/wiki/COFF) and
[ELF](https://en.wikipedia.org/wiki/Executable_and_Linkable_Format) as well as
unexpected file formats like
[verilog hex memory dumps](https://binutils.sourceware.narkive.com/aoJ9J4Xk/patch-verilog-hex-memory-dump-backend-for-bfd).

Here we will concentrate on the BFD ELF APIs.

The API is split into multiple files which include:

 - [bfd/bfd-in.h](https://github.com/bminor/binutils-gdb/blob/binutils-2_34/bfd/bfd-in.h) - top level generic APIs including `bfd_hash_table`
 - [bfd/bfd-in2.h](https://github.com/bminor/binutils-gdb/blob/binutils-2_34/bfd/bfd-in2.h) - top level binary file APIs including `bfd` and `asection`
 - [include/bfdlink.h](https://github.com/bminor/binutils-gdb/blob/binutils-2_34/include/bfdlink.h) - generic bfd linker APIs including `bfd_link_info` and `bfd_link_hash_table`
 - [bfd/elf-bfd.h](https://github.com/bminor/binutils-gdb/blob/binutils-2_34/bfd/bfd-elf.h) - extentions to the APIs for ELF binaries including `elf_link_hash_table` 
 - `bfd/elf{wordsize}-{architecture}.c` - architecture specific implementations

For each architecture implementations are defined in `bfd/elf{wordsize}-{arch}.c`.  For
example for OpenRISC we have
[bfd/elf32-or1k.c](https://github.com/bminor/binutils-gdb/blob/binutils-2_34/bfd/elf32-or1k.c).

Throughout the linker code we see access to the BFD Linker and ELF apis.

Some key
symbols include.

 - `info` - A reference to `bfd_link_info`
 - `htab` - Refers to to `or1k_elf_hash_table (info)`, hash table which stores
   generic link state and arch specific state, contains:
    - `htab->root.splt` - the `.plt` section
    - `htab->root.sgot` -  the `.got` section
    - `htab->root.srelgot` - the `.relgot` section (relocations against the got)
    - `htab->root.sgotplt` - the `.gotplt` section
    - `htab->root.dynobj` - a special bfd to which sections are created (created in `or1k_elf_check_relocs`)
 - `sym_hashes` - From `elf_sym_hashes (abfd)` hash table for global
   symbols.
 - `h` - A pointer to a `struct elf_link_hash_entry`, represents a global symbol
 - `local_tls_type` - Retrieved by `elf_or1k_local_tls_type(abfd)` entry to store `tls_type` if not a global symbol,
   when `h` is `NULL`.


   in `sym_hashes`.
how many got entries required
   got.offset where in the got the value is

Now that we have a bit of understanding of the [data structures](https://lwn.net/Articles/193245/)
we can look to the link alforithm.

The link process in the GNU Linker can be thought of in phases.

### Phase 1 - Book Keeping (check_relocs)

The `or1k_elf_check_relocs` function is called during the first phase to
validate relocations, returns FALSE if there are issues.  It also does
some book keeping.

```
static bfd_boolean
or1k_elf_check_relocs (bfd *abfd,
                       struct bfd_link_info *info,
                       asection *sec,
                       const Elf_Internal_Rela *relocs)

#define elf_backend_check_relocs        or1k_elf_check_relocs
```

The arguments being:

  - `abfd`   - The current elf object file we are working on
  - `sec`    - The current elf section we are working on
  - `info`   - The bfd API
  - `relocs` - The relocations from the current section

For local symbols:

```
      ...
      else
	{
	  unsigned char *local_tls_type;

	  /* This is a TLS type record for a local symbol.  */
	  local_tls_type = (unsigned char *) elf_or1k_local_tls_type (abfd);
	  if (local_tls_type == NULL)
	    {
	      bfd_size_type size;

	      size = symtab_hdr->sh_info;
	      local_tls_type = bfd_zalloc (abfd, size);
	      if (local_tls_type == NULL)
		return FALSE;
	      elf_or1k_local_tls_type (abfd) = local_tls_type;
	    }
	  local_tls_type[r_symndx] |= tls_type;
	}

	      ...
	      else
		{
		  bfd_signed_vma *local_got_refcounts;

		  /* This is a global offset table entry for a local symbol.  */
		  local_got_refcounts = elf_local_got_refcounts (abfd);
		  if (local_got_refcounts == NULL)
		    {
		      bfd_size_type size;

		      size = symtab_hdr->sh_info;
		      size *= sizeof (bfd_signed_vma);
		      local_got_refcounts = bfd_zalloc (abfd, size);
		      if (local_got_refcounts == NULL)
			return FALSE;
		      elf_local_got_refcounts (abfd) = local_got_refcounts;
		    }
		  local_got_refcounts[r_symndx] += 1;
		}
```

The above is pretty straight forward and we can read as:

 - First part is for storing `TLS` type intformation:
    - If the `local_tls_type` array is not initialized:
       - Allocate 1 entry for each local variable
    - Record the tls type in `local_tls_type` for the current symbol

 - Second part is for recording `.got` section references:
    - If the `local_got_refcounts` array is not initialized:
       - Allocate it, 1 entry per each local variable
    - Record a reference by incrementing `local_got_refcounts` for the current symbol

For global symbols, its much more easy we see:

```
      ...
      if (h != NULL)
	  ((struct elf_or1k_link_hash_entry *) h)->tls_type |= tls_type;
      else
      ...

	      if (h != NULL)
		h->got.refcount += 1;
	      else
		...
```

As the `tls_type` and `refcount` fields are available directly on each
`hash_entry` handling global symbols is much easier.

 - First part is for storing `TLS` type intformation:
    - Record the tls type in `tls_type` for the current `hash_entry`
 - Second part is for recording `.got` section references:
    - Record a reference by incrementing `got.refcounts` for the `hash_entry`

### Phase 2 - creating space (size_dynamic_sections + _bfd_elf_create_dynamic_sections)

The `or1k_elf_size_dynamic_sections()` function iterates over all object
files to calculate the size of stuff.  The `_bfd_elf_create_dynamic_sections()`
function does the actual section allocation, we use the generic version.

Setting up the sizes of the `.got` section (global offset table) and `.plt`
section (procedure link table)


The definition is as below:

```
static bfd_boolean
or1k_elf_size_dynamic_sections (bfd *output_bfd ATTRIBUTE_UNUSED,
                                struct bfd_link_info *info)

#define elf_backend_size_dynamic_sections       or1k_elf_size_dynamic_sections
#define elf_backend_create_dynamic_sections     _bfd_elf_create_dynamic_sections
```

The arguments to `or1k_elf_size_dynamic_sections` being:

 - `output_bfd` - **Unused**, the output elf object
 - `info` - the API to BFD provides access to everything

Internally the function uses:

 - `htab` - from `or1k_elf_hash_table (info)`
    - `htab->root.dynamic_sections_created` - `true` if sections like `.interp` have been created by the linker
 - `ibfd` - a `bfd *` reference from `info->input_bfds`, used to iterate over all input elf objects

During the first part we setup got sizes for relocation entry size for local
symbols with this code:

```
  /* Set up .got offsets for local syms, and space for local dynamic
     relocs.  */
  for (ibfd = info->input_bfds; ibfd != NULL; ibfd = ibfd->link.next)
    {
      ...
      local_got = elf_local_got_refcounts (ibfd);
      if (!local_got)
	continue;

      symtab_hdr = &elf_tdata (ibfd)->symtab_hdr;
      locsymcount = symtab_hdr->sh_info;
      end_local_got = local_got + locsymcount;
      s = htab->root.sgot;
      srel = htab->root.srelgot;
      local_tls_type = (unsigned char *) elf_or1k_local_tls_type (ibfd);
      for (; local_got < end_local_got; ++local_got)
	{
	  if (*local_got > 0)
	    {
	      unsigned char tls_type = (local_tls_type == NULL)
					? TLS_UNKNOWN
					: *local_tls_type;

	      *local_got = s->size;
	      or1k_set_got_and_rela_sizes (tls_type, bfd_link_pic (info),
					   &s->size, &srel->size);
	    }
	  else
	    *local_got = (bfd_vma) -1;

	  if (local_tls_type)
	    ++local_tls_type;
	}
    }

```

Here, for example, we can see we iterate over each input elf object `ibfd`
and each `local_got` try  and update `s->size` `srel->size` to account for
the size.  Allocating space for local symbols.

The above can be read as:

  - For each `local_got` entry:
    - If the local symbol is used in the `.got` section:
      - Get the `tls_type`
      - Set the offset `local_got` to the section offset `s->size`
      - Update `s->size` and `srel->size` using `or1k_set_got_and_rela_sizes()`
   - If the local symbol is not used in the `.got` section:
      - Set the offset `local_got` to the `-1`, to indicate not used

In the next part we allocate space for all dynamic symbols by iterating
through symbols with the `allocate_dynrelocs` iterator.  Here we call:

```
  elf_link_hash_traverse (&htab->root, allocate_dynrelocs, info);
```

Inside `allocate_dynrelocs()` record the space used for relocations and
the `.got` and `.plt` sections.  Example:

```
  if (h->got.refcount > 0)
    {
      asection *sgot;
      bfd_boolean dyn;
      unsigned char tls_type;

      ...
      sgot = htab->root.sgot;

      h->got.offset = sgot->size;

      tls_type = ((struct elf_or1k_link_hash_entry *) h)->tls_type;

      dyn = htab->root.dynamic_sections_created;
      dyn = WILL_CALL_FINISH_DYNAMIC_SYMBOL (dyn, bfd_link_pic (info), h);
      or1k_set_got_and_rela_sizes (tls_type, dyn,
				   &sgot->size, &htab->root.srelgot->size);
    }
  else
    h->got.offset = (bfd_vma) -1;
```

The above, with `h` being our symbol a reference to `struct elf_link_hash_entry
*`,  can be read as:

  - If the symbol will be in the `.got` section:
    - Get the global reference to the `.got` section and put it in `sgot`
    - Set the got lococation `h->got.offset` for the symbol to the current got
      section size `htab->root.sgot`.
    - Set `dyn` to `true` if we will be doing a dynamic link.
    - Call `or1k_set_got_and_rela_sizes()` to update the sizes for the `.got`
      and `.got.rela` sections.
  - If the symbol is going to be in the `.got` section:
    - Set the got location `h->got.offset` to `-1`

The function `or1k_set_got_and_rela_sizes()` used above is used to increment
`.got` and `.rela` section sizes accounting for if these are TLS symbols, which
need additional entries and relocations.



### Phase 3 - linking (relocate_section)

Notes from above
  - For symbol, write got section, write rela if dynamic
     - Write entry to GOT only once per symbol
  - run or1k_final_link_relocate
     - Write actual value to text

 - `local_got_offsets` - From `elf_local_got_offsets (input_bfd)` the got
   offsets for symbols setup in Phase 4.

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

During phase 3 above we wrote the `.text` section out to files.  During the
final finishing up phase we need to write the remaining sections.  This
includes the `.plt` and `.got` sections.

This also includes the `.plt.rela` and `.got.rela` sections which contain
dynamic relocation entries.

Writing of the data sections is handled by
[or1k_elf_finish_dynamic_sections()](https://github.com/bminor/binutils-gdb/blob/binutils-2_34/bfd/elf32-or1k.c#L2178)
and writing of the relocation sections is handled by
[or1k_elf_finish_dynamic_symbol()](https://github.com/bminor/binutils-gdb/blob/binutils-2_34/bfd/elf32-or1k.c#L2299).  These are defined as below.

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

A snippet for the `or1k_elf_finish_dynamic_sections()` shows how when writing to
the `.plt` section assembly code needs to be injected.  This is where the first
entry in the `.plt` section is written.

```
          else if (bfd_link_pic (info))
            {
              plt0 = OR1K_LWZ(15, 16) | 8;      /* .got+8 */
              plt1 = OR1K_LWZ(12, 16) | 4;      /* .got+4 */
              plt2 = OR1K_NOP;
            }
          else
            {
              unsigned ha = ((got_addr + 0x8000) >> 16) & 0xffff;
              unsigned lo = got_addr & 0xffff;
              plt0 = OR1K_MOVHI(12) | ha;
              plt1 = OR1K_LWZ(15,12) | (lo + 8);
              plt2 = OR1K_LWZ(12,12) | (lo + 4);
            }

          or1k_write_plt_entry (output_bfd, splt->contents,
                                plt0, plt1, plt2, OR1K_JR(15));

          elf_section_data (splt->output_section)->this_hdr.sh_entsize = 4;
```

Here we see a write to `output_bfd`, this represents the output object file
which we are writing to.  The argument `splt->contents` represents the object
file offset to write to for the `.plt` section. Next we see the line
`elf_section_data (splt->output_section)->this_hdr.sh_entsize = 4`
this allows the linker to calculate the size of the section.

A snippet from the `or1k_elf_finish_dynamic_symbol()` function shows where
we write out the code and dynamic relocation entries for each symbol to
the `.plt` section.

```
      splt = htab->root.splt;
      sgot = htab->root.sgotplt;
      srela = htab->root.srelplt;
      ...

      else
        {
          unsigned ha = ((got_addr + 0x8000) >> 16) & 0xffff;
          unsigned lo = got_addr & 0xffff;
          plt0 = OR1K_MOVHI(12) | ha;
          plt1 = OR1K_LWZ(12,12) | lo;
          plt2 = OR1K_ORI0(11) | plt_reloc;
        }

      or1k_write_plt_entry (output_bfd, splt->contents + h->plt.offset,
                            plt0, plt1, plt2, OR1K_JR(12));

      /* Fill in the entry in the global offset table.  We initialize it to
         point to the top of the plt.  This is done to lazy lookup the actual
         symbol as the first plt entry will be setup by libc to call the
         runtime dynamic linker.  */
      bfd_put_32 (output_bfd, plt_base_addr, sgot->contents + got_offset);

      /* Fill in the entry in the .rela.plt section.  */
      rela.r_offset = got_addr;
      rela.r_info = ELF32_R_INFO (h->dynindx, R_OR1K_JMP_SLOT);
      rela.r_addend = 0;
      loc = srela->contents;
      loc += plt_index * sizeof (Elf32_External_Rela);
      bfd_elf32_swap_reloca_out (output_bfd, &rela, loc);
```

Here we can see we write 3 things to `output_bfd` for the single `.plt` entry.
We write:
  - The assembly code to the `.plt` section.
  - The `plt_base_addr` (the first entry in the `.plt` for runtime lookup) to the `.got` section.
  - And finally a dynamic relocation for our symbol to the `.plt.rela`.

## GLIBC Runtime Linker

The runtime linker, also referred to as the dynamic linker, will do the final
linking as we load our program and shared libraries into memory.  It can process
a limited set of relocation entries that were setup above during Phase 4 of
linking.

The runtime linker implementation is found mostly in the
`elf/dl-*` GLIBC source files.  Dynamic relocation processing is handled in by
the [_dl_relocate_object()](https://github.com/stffrdhrn/or1k-glibc/blob/or1k-port-1/elf/dl-reloc.c#L146)
function in the `elf/dl-reloc.c` file.  The back end macro used for relocation
[ELF_DYNAMIC_RELOCATE](https://github.com/stffrdhrn/or1k-glibc/blob/or1k-port-1/elf/dynamic-link.h#L194)
is defined across several files including `elf/dynamic-link.h`
and [elf/do-rel.h](https://github.com/stffrdhrn/or1k-glibc/blob/or1k-port-1/elf/do-rel.h)
Architecture specific relocations are handled by the function `elf_machine_rela()`, the implementation
for OpenRISC being in [sysdeps/or1k/dl-machine.h](https://github.com/stffrdhrn/or1k-glibc/blob/or1k-port-1/sysdeps/or1k/dl-machine.h#L210).

In summary from top down:
 - [elf/rtld.c](https://github.com/stffrdhrn/or1k-glibc/blob/or1k-port-1/elf/rtld.c) - implements `dl_main()` the top level entry for the dynamic linker.
 - [elf/dl-open.c](https://github.com/stffrdhrn/or1k-glibc/blob/or1k-port-1/elf/dl-open.c) - function `dl_open_worker()` calls `_dl_relocate_object()`, you may also recognize this from [dlopen(3)](https://man7.org/linux/man-pages/man3/dlopen.3.html).
 - [elf/dl-reloc.c](https://github.com/stffrdhrn/or1k-glibc/blob/or1k-port-1/elf/dl-reloc.c) - function `_dl_relocate_object` calls `ELF_DYNAMIC_RELOCATE`
 - `elf/dynamic-link.h` - defined macro `ELF_DYNAMIC_RELOCATE` calls `elf_dynamic_do_Rel()` via several macros
 - `elf/do-rel.h` - function `elf_dynamic_do_Rel()` calls `elf_machine_rela()`
 - `sysdeps/or1k/dl-machine.h` - architecture specific function `elf_machine_rela()` implements dynamic relocation handling

It supports relocations for:
 - `R_OR1K_NONE` - do nothing
 - `R_OR1K_COPY` - used to copy initial values from shared objects to process memory.
 - `R_OR1K_32` - a `32-bit` value
 - `R_OR1K_GLOB_DAT` - aligned `32-bit` values for `GOT` entries
 - `R_OR1K_JMP_SLOT` - aligned `32-bit` values for `PLT` entries
 - `R_OR1K_TLS_DTPMOD/R_OR1K_TLS_DTPOFF` - for shared TLS GD `GOT` entries
 - `R_OR1K_TLS_TPOFF` - for shared TLS IE `GOT` entries

A snippet of the OpenRISC implementation of `elf_machine_rela()` can be seen
below.  It is pretty straight forward.

```
/* Perform the relocation specified by RELOC and SYM (which is fully resolved).
   MAP is the object containing the reloc.  */

auto inline void
__attribute ((always_inline))
elf_machine_rela (struct link_map *map, const Elf32_Rela *reloc,
                  const Elf32_Sym *sym, const struct r_found_version *version,
                  void *const reloc_addr_arg, int skip_ifunc)
{

      struct link_map *sym_map = RESOLVE_MAP (&sym, version, r_type);
      Elf32_Addr value = SYMBOL_ADDRESS (sym_map, sym, true);

     ...
      switch (r_type)
        {
          ...
          case R_OR1K_32:
            /* Support relocations on mis-aligned offsets.  */
            value += reloc->r_addend;
            memcpy (reloc_addr_arg, &value, 4);
            break;
          case R_OR1K_GLOB_DAT:
          case R_OR1K_JMP_SLOT:
            *reloc_addr = value + reloc->r_addend;
            break;
          ...
        }
}

```

## Summary

We have looked at how symbols move from the Compiler, to Assembler, to Linker to
Runtime linker.

## Further Reading
- [GCC Passes](% post_url 2018-06-03-gcc_passes %}) - My blog entry on GCC passes
- [bfdint](http://cahirwpz.users.sourceforge.net/binutils-2.26/bfd-internal.html/index.html#SEC_Contents) - The BFD developer's manual
- [ldint](http://home.elka.pw.edu.pl/~macewicz/dokumentacja/gnu/ld/ldint_2.html) - The LD developer's manual
- [LD and BFD Gist](https://gist.github.com/stffrdhrn/d59e1d082430a48643b301c13f6f4d24) - Dump of notes I collected while working on this article.
