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

Lets pick a simple inverting op amp circuit.  We can use the spice models from vendors like [ti](http://www.ti.com/) to plug into our schematics. This also means we can easily, virtually, swap out components like op amps to see how they perform in our design. 

Below we can see our completed for a non-inverting op amp with a dual power supply, the input will be amplified 25 times. For more details on drawing schematics in kicad refer to the [getting started tutorials](http://www.kicad-pcb.org/display/KICAD/Tutorials). 

![kicad amp for demo]({{site.url}}/content/kikcad-spicedemo-2015-04-23_07-56-40.png)

Once out circuit is complete we can generate a spice netlist by navigating to **Tools > Generate Netlist**. 

![Generating a netlist in kicad]({{site.url}}/content/kicad-spicedemo-netlist-2015-04-23_22-24-24.png)

Some comments on the Netlist options:

* I have selected *Prefix references 'U' and 'IC' with 'X'*, this is needed for `ngspice` as it recognizes 'X' components as subcircuits. 
* Test 

This will generate a netlist like the following:

```
* EESchema Netlist Version 1.1 (Spice format) creation date: Sat 25 Apr 2015 07:04:41 AM JST

* To exclude a component from the Spice Netlist add [Spice_Netlist_Enabled] user FIELD set to: N
* To reorder the component spice node sequence add [Spice_Node_Sequence] user FIELD and define sequence: 2,1,0

*Sheet Name:/
XU1  7 6 0 4 1 OPAMP            
J1  2 0 0 JACK_IN              
J2  7 3 0 JACK_OUT             
R2  6 7 50K             
R1  2 6 2K              
R3  0 3 2K              
P1  4 0 1 PWR_IN               

.end

```

Create a `components.cir`

Download your components spice model

http://www.ti.com/product/lmv981-n

![TI Spice Models]({{site.url}}/content/kicad-spicedemo-timodel.png)

```
* Components and subcircuits for use in spicedemo.cir

.INCLUDE LMV981.MOD

* 4 0 1 PWR_IN
*              + g -     
.subckt PWR_IN 1 2 3
  Vneg 1 2  3.3V
  Vpos 2 3 3.3V
.ends PWR_IN

* 7 6 0 4 1 OPAMP
*             o - + p n
.subckt OPAMP 1 2 3 4 5
  * PINOUT ORDER  1   3   6  2  4   5
  * PINOUT ORDER +IN -IN +V -V OUT NSD
  Xopamp 3 2 4 5 1 NSD LMV981
.ends OPAMP

*               s x g
.subckt JACK_IN 1 2 3
  *** Simulate mic input A-note
  Vmic  3 1 ac SIN(0 0.02 440)
.ends JACK_IN

*                s x g
.subckt JACK_OUT 1 2 3
  Rwire  1 2   10ohm
.ends JACK_OUT

```


Modify the generated netlist slightly to include the `components.cir` and perform the analysis we wish to do. 

```
.include components.cir

*Sheet Name:/
XU1  7 6 0 4 1 OPAMP
XJ1  2 0 0 JACK_IN
XJ2  7 3 0 JACK_OUT
R2  6 7 50K
R1  2 6 2K
R3  0 3 2K
XP1  4 0 1 PWR_IN

.tran 0.1m 3m
.plot tran V(7) V(2)

.ac dec 10 1 100K
.plot ac V(7)

.end 
```

Run the `OP` analsysis, to make sure nothing is shorted

Run the `TRAN` analsysis, to make sure it works

```
> tran 0.3m 1m
> plot V(2) V(7)
```

![TRAN analysis results]({{site.url}}/content/kicad-spicedemo-tran.png)

Run the `AC` analysis, to analysis the performance

```
> ac dec 10 1 100K
No. of Data Rows : 51
> plot V(2) V(7)
```

![Ac analysis results]({{site.url}}/content/kicad-spicedemo-ac.png)
