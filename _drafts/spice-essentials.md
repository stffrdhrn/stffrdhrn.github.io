---
layout: post
title: Spice Essentials
---

Recently I have been searching for good [linux tools to simulate circuits](http://en.wikipedia.org/wiki/List_of_free_electronics_circuit_simulators).  SPICE is widely known in circuit simulation. Below are my tips of the essentials to get started with SPICE. This also includes how to perform graphical plotting with nutmeg.  All the below demos are using ngspice and  ngnutmeg. 

When analysing a circuit one needs to use the analysis commands.  These are 
usually placed at the end of your circuit description. 

## Using OP

The first analysis command one should know is the ```OP``` command. It provides dcoperating point voltage dump  of all nodes with capacitors fully charged (no 
current) and inductors fully inducting (shorted). 

```
* Voltage divider 
* file: vdiv.cir
V1 2 0 DC 10V
R1  2 1 50K             
R2  1 0 20K             

.op
.end
```
When running we can see that the voltage and current of each node is displayed.

{% highlight bash %}
$ ngspice -b vdiv.cir

No. of Data Rows : 1
        Node                                  Voltage
        ----                                  -------
        ----    -------
        V(1)                             2.857143e+00
        V(2)                             1.000000e+01

        Source  Current
        ------  -------

        v1#branch                        -1.42857e-04
{% endhighlight %}

## Using TRAN

The next analysis command to know is the =TRAN= command. 

TRAN will run the circuit for a fixed time and take inteval mesurements.  The below =tran 0.1m 5m= will measure for 5 milliseconds and output every 0.1 milliseconds.  It shout print 50 readings.   You should choose your measurements based on the frequency of the input source to allow you to see the full wave, 

Example

.tran STEP END <START> <MAX>
 - STEP - is the time interval of how often a measurement is taken
 - END - is the time when spice will end measurement
 - START - (default 0) is the time when spice will start measurement
 - MAX - is used to define a STEP smaller than STEP (yeah confusing)

Note: the step time of TRAN is not always used by spice.  If END-START/50 is less than STEP spice will set step to the smaller value. 

Example
.tran 0.01m 1m
  - STEP = 0.01m (milliseconds)
  - STEP-Sprice = 1m - 0 / 50 = 0.02 (millisecconds) 
  - SAMPLES = 1 / 0.01
Since we specified 0.01 is smaller than 0,02, spice will use the sample interval of 0.01.  This will sample 100 samples (1 / 0.01). 

Example - picking step values
Input 440hz, Want to measure 100 samples of full wave form. 

(1 / 440) = 2.27ms 

## Controlling Output

To output data during simulation we use the =PLOT= and =PRINT= commands. 

.plot tran v(1), v(8)

## Running Spice

When simulating a circuit we have a few options. Batch analsyis, below, will 
just  run the commands (plot/print) in the circuilt and output the results.

ngspice -b preamp.cir

Another option is to output the analysis data in raw format. This data can then
be loaded up in ngnutmeg for further analysis.

# This commands runs the analysis in preamp.cir and outputs raw data to
# preamp.raw
ngspice -b preamp.cir -r preamp.raw

# The below command loads the raw file into ngnutmeg and then displays the
# ngntmeg prompt. The =plot v(1)= command will bring up a view of the wave form
ngnutmeg preamp.raw
> plot v(1)-5 v(8)

## Further Reading

Keep an eye on this [stack exchange discussion](http://electronics.stackexchange.com/questions/55087/spice-simulator-at-linux) which mentions other tools for simulating circuits in linux. 

These guides have been very help:

* [bwrcs.eecs.berkeley.edu](http://bwrcs.eecs.berkeley.edu/Classes/IcBook/SPICE/UserGuide/analyses_fr.html) - great reference, this links directly into analysis
* [www.allaboutcircuits.com](http://www.allaboutcircuits.com/vol_5/chpt_7/8.html) - simple intro to netlists
* [vision.lakeheadu.ca](http://vision.lakeheadu.ca/eng4136/spice/op_ac_analysis.html) - tutorial on running ngspice
* [www.seas.upenn.edu](http://www.seas.upenn.edu/~jan/spice/spice.filter.html) - good example of ac analysis
* [dev.man-online.org](http://dev.man-online.org/man1/ngnutmeg/) - ngnutmeg manual


