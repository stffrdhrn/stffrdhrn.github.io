---
title: Setting up USB-Blaster in Linux
layout: post
date: 2014-07-30 22:50

---

Today I got my [DE0-Nano](http://www.terasic.com.tw/cgi-bin/page/archive.pl?No=593) 
FPGA development board.  The first thing I wanted to do
was get the linux connectivity setup; so I plugged it in. The device was 
recognized as character devide by linux right away. 

Next, I checked Altera to see if anything special was needed for getting
USB-Blaster setup in linux. They recommend some 
[udev example rules](http://www.altera.com/download/drivers/dri-usb_b-lnx.html)
 to make the device file world writable, I guess that is ok. I dropped the 
udev rules into `/etc/udev/rules.d/51-usbblaster.rules` and unplugged
then replugged the device but nothing happened. 

I havent setup udev rules for a while, so right away I wasn't able to spot the 
problem. I was able to test the rules using udevadm as below. 

{% highlight bash %}
    udevadm test /bus/usb/devices/3-1
{% endhighlight %}

It complained that `BUS` and `SYSFS` matchers were not valid.  They were 
removed in 2011 according to the udev changelog. 

Using `udevadm` we are also able to see the proper attributes that we should 
be matching.  Below is what I have come up with to properly setup USB-Blaster
in linux. I am using Fedora 18.

{% highlight bash %}
    udevadm info -a --path=/sys/bus/usb/devices/3-1
{% endhighlight %}

## My USB-Blaster udev Rules

{% highlight bash %}
    # USB-Blaster
    SUBSYSTEM=="usb", ATTRS{idVendor}=="09fb", ATTRS{idProduct}=="6001", MODE="0666", SYMLINK="usbblaster%n"
    SUBSYSTEM=="usb", ATTRS{idVendor}=="09fb", ATTRS{idProduct}=="6002", MODE="0666"
    SUBSYSTEM=="usb", ATTRS{idVendor}=="09fb", ATTRS{idProduct}=="6003", MODE="0666"
    #
    # # USB-Blaster II
    SUBSYSTEM=="usb", ATTRS{idVendor}=="09fb", ATTRS{idProduct}=="6010", MODE="0666"
    SUBSYSTEM=="usb", ATTRS{idVendor}=="09fb", ATTRS{idProduct}=="6810", MODE="0666"
{% endhighlight %}

Note, since my device is a `6001` I setup a symlink which will make it easily 
accessable at `/dev/usbblaster1`.
    
Hopefully this will be helpful to other people. 
