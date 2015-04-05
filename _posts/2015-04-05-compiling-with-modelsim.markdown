---
title: Compiling Verilog on the Command Line with ModelSim
layout: post
date: 2015-04-05 09:!8

---

Lately I have been working on [some projects](https://github.com/stffrdhrn?tab=repositories) 
using verilog.  My main development environment has been Altera Quartus  II which 
works fine. However, when compiling in quartus we don't always need to go through 
all of the sythesis and timing steps, instead one may just need to verify their 
source code is compilable. 

The `vlog` compiler included with [ModelSim](http://en.wikipedia.org/wiki/ModelSim) 
can be used to quickly compile your HDL.

{% highlight bash %}
    # Add module sim onto your path
    $ PATH=$PATH:/usr/share/altera/14.0/modelsim_ase/bin

    # Create a library directory used by 'vlog', by default it looks for 'work'
    $ vlib work

    # Perform the verilog compile
    $ vlog dram_controller.v

    Model Technology ModelSim ALTERA vlog 10.1e Compiler 2013.06 Jun 12 2013
    -- Compiling module dram_controller

    Top level modules:
	    dram_controller
{% endhighlight %}

You can find more details on using ModelSim's command line utilties at the [ncsu 
modelsim tutorial](http://www.eda.ncsu.edu/wiki/Tutorial:Modelsim_Tutorial). 
