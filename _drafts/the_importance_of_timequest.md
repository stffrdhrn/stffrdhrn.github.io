---
title: The Importance of TimeQuest
layout: post
date: 2015-06-04
---

`Outline only`

I have been working on few [fpga projects](https://github.com/stffrdhrn) recently and didn't have any issues with
timing constraints.  However when running my sdram conroller at 100Mhz I found a need for [Altera TimeQuest](https://www.altera.com/support/software/timequest/sof-qts-timequest.html).

## Why?
Timequest is used both for your design verification and synthesis.  

If you compile your fpga project in quartus without setting up timing constraints it will not put any limits on signal propagation.  If we do setup constraints the compiler will try its best to choose logic to meet timing constraints. 



I found this out the hard way as after compiling my sdram controller I went to check timing on it and found several longest path violations.  However, after integrating timing contraints into the compile process my design was able to make timing without any design changes. 

## How?

```
create_clock -period "20ns" CLOCK_50
derive_pll_clocks
derive_clock_uncertainty
```

## Further Readings

- http://www.alterawiki.com/wiki/TimeQuest_User_Guide - read the pdf linked here
- https://www.synopsys.com/Community/Interoperability/Pages/TapinSDC.aspx - synopsys explaination to sdc format
