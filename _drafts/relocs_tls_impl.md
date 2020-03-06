---
title: Relocation Implementation Details
layout: post
date: 2019-11-29 06:47
categories: [ hardware, embedded, openrisc ]
---


# The GNU toolchain

GCC -> BFD -> GLIBC

## BFD

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

### Phase 1 - book keeping (check_relocs)

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

### GCC

In gcc we have riscv_legitimize_tls_address.  It takes an address of a variable
and generates code to load that address based on variable properties.

Global Variable - code will be GOTBASE + offset - the offset is determined at link time

 - GD - code will load MOD and OFF from GOT and call __tls_get_addr
 - LD - code will load a base with __tls_get_addr, then use local offsets for subsequent calls to load variable addresses.  Useful if more than one variables are accessed, it saves from having to do multiple calls to __tls_get_addr.
 - IE - code will load OFFSET got  and add to TP directly
    - tp = TP
    - tmp = load ie from GOT
    - res = tmp + tp
 - LOCAL - code wil$l have OFFSET directly + TP directly - offset is determined as link time
    - tmp = tls offset
    - res = tp + tmp

## Relaxation

As some TLS access methods are more efficient than others we would like to choose
the best method for each variable access.  However, we don't
always know where a variable will come from until link time.

One time of relaxation performed by the linker is GD to IE relaxation.  During compile
time GD relocation may be choosen for `extern` variables.  However, during link time
the variable may be in the same module i.e. not a shared object which would require
GD access.

If relaxation can be done.
Relaxation will rewrite the GD access code in the .text section of the binary and
convert it to IE access.


## Further Reading
- Bottums Up - http://bottomupcs.sourceforge.net/csbu/x3735.htm
- GOT and PLT - https://www.technovelty.org/linux/plt-and-got-the-key-to-code-sharing-and-dynamic-libraries.html
- Android - https://android.googlesource.com/platform/bionic/+/HEAD/docs/elf-tls.md
- Oracle - https://docs.oracle.com/cd/E19683-01/817-3677/chapter8-20/index.html
- Drepper - https://www.akkadia.org/drepper/tls.pdf
- Deep Dive - https://chao-tic.github.io/blog/2018/12/25/tls
