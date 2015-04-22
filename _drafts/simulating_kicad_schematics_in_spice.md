---
title: Simulating kicad schematics in spice
layout: post
date: 2015-04-23
---

[KiCad](http://www.kicad-pcb.org) is a great tool for taking your electronics design from schematic to PCB, but circuit simulation is out of scope. 

As we will see here KiCad does contain some great features to generate netlists which can be used with simulators like `ngspice` to do simulations. 

First we will need to start off with a schematic. I will choose a circuit which will allow us to do the following:

- Use vendor spice component 
- Perform Transient Analysis
- Simulate an input signal

Lets pick a simple inverting op amp circuit.  We can use the spice models from vendors like ti to plug into our schematics. 

![kicad amp for demo]({{site.url}}/content/kikcad-spidedemo-2015-04-23_07-56-40.png)
