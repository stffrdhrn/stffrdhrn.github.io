---
title: The Importance of TimeQuest
layout: post
date: 2015-06-04
---

`Outline only`
I have been working on few [fpga projects] recently and didn't have any issues with
timing.  However when trying to testing my sdram conroller at 100Mhz I found a needfor [Altera TimeQuest](https://www.altera.com/support/software/timequest/sof-qts-timequest.html).

## Why?
Timequest is used both for your design verification and synthesis.  

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
