---
title: Debugging GDB in GDB
layout: post
date: 2017-05-19
---

For the last year or so I have been working on getting a gdb port
upstreamed for OpenRISC.  One thing you sometimes have to do when working
on gdb itself is debug it.  But, it could be a bit confusing to debug gdb in gdb
because both are interactive GUI's.

In general there are 2 tips that will help

## Setting the Prompt

Setting the prompt of the top level gdb will help so you know which gdb
command line.  I do that with `set prompt (master:gdb) `, (having space
after the `(master:gdb)` is recommended).

## Handling SIGINT

Handling `ctrl-c` is another thing we need to consider.  If you are in your
inferior gdb and you press `ctrl-c` which gdb will you stop?  The top level
gdb or the inferior gdb?

## All together

An example session may look like the following

```
$ gdb or1k-elf-gdb

(gdb) set prompt (master:gdb) 
(master:gdb) handle SIGINT pass
(master:gdb) run


```
## Other Options

You could also remote debug from a different terminal.  But I find having
everything in one terminal more easy.

## Further References
- https://sourceware.org/gdb/current/onlinedocs/gdb/Prompt.html#Prompt
- https://sourceware.org/gdb/current/onlinedocs/gdb/Signals.html#Signals
- https://sourceware.org/gdb/onlinedocs/gdb/Attach.html#Attach
