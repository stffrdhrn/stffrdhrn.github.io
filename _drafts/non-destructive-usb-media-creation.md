---
title: Non Destructive USB Install Media Creation
layout: post
date: 2015-11-03 22:23

categories: [ 'Linux' ]
tags:
 - linux
---

If you want to try out linux installs or Live images for Fedora and other
distributions you may find that they require you to erase the contents of a 
USB drive to store the install media image. 
This guide should help you save your usb drive content and simply copy the 
relevant install files onto the install media. 

I was recently installing Fedora 23 on my new Skylake machine and did not 
have a dvd drive available. 

Usually install media will be provided as either and `iso` or a usb disk `img`
file. One may want to pull files off of the install media for, for example, 
build a PXE install server or copy files to an existing USB drive to use for 
installation without completely erasing the USB drive contents. 

With the `iso` image the data contained is in the format of as single [iso 9660](https://en.wikipedia.org/wiki/ISO_9660) file system. 
With an `iso` file [most people know how](http://serverfault.com/questions/198135/how-to-mount-an-iso-file-in-linux) 
to `mount` the file and read data. However how do you mount a disk image, what kind of filesystem does it contain?

Now, if `mount` allows your to mount a filesystem like a `.iso` file or device file like `/dev/sda1`. How can you mount a parition table? This is where `kpartx` comes to help, but first lets talk a bit about UEFI (the reason images are GPT
paritioned). 

## Unified Extensible Firmware Interface (UEFI)
Modern systems running [UEFI](https://en.wikipedia.org/wiki/Unified_Extensible_Firmware_Interface) firmware are able to boot disks partitioned with a [GUID partition table](https://en.wikipedia.org/wiki/GUID_Partition_Table) and
containing FAT file systems. 

In this case our `img` file data is actually a full partitioned disk image. 
When our OS boots it will locate disk paritions and allocated them to device 
handles like `/dev/sda1` or `/dev/sda2`.  How can you tell the os to create 
filesystem device nodes for a non phyiscal disk? You guessed it...

## kpartx 
The `kpartx` tool is a utility which maps disk image paritions to `/dev/` device nodes. 

## Demo

In my case I wanted to uses the `memtest86-usb.img` image from [memtest86](http://www.memtest86.com/download.htm) to do some memory tests before installing Fedora on my new system. 

```
# Inspect the file type
$ file memtest86-usb.img  
memtest86-usb.img: SYSLINUX GPT-MBR (version 4.00 or newer)

# List the partitions in the disk image
$ sudo kpartx -l memtest86-usb.img
loop0p1 : 0 100353 /dev/loop0 2048
loop0p2 : 0 202719 /dev/loop0 104448
loop deleted : /dev/loop0

# Create parition devides
$ sudo kpartx -a -v memtest86-usb.img
add map loop0p1 (253:3): 0 100353 linear /dev/loop0 2048
add map loop0p2 (253:4): 0 202719 linear /dev/loop0 104448

# See what we created
ls /dev/mapper
loop0p1
loop0p2

# Mount the partitions
sudo mount /dev/mapper/loop0p1 /mnt

# When complete you should unmount your paritions and remove the mappings
sudo umount /mnt
sudo kpartx -d -v memtest86-usb.img

```
Once your partitions are mounted you should be able to copy to the `EFI` folder
to any FAT formatted USB media and create a bootable drive. 

## Further Reading

- [UEFI: how does it really work?](https://www.happyassassin.net/2014/01/25/uefi-boot-how-does-that-actually-work-then/) - Great guide on how UEFI systems boot
- [Mounting raw image files and kpartx](https://nfolamp.wordpress.com/2010/08/16/mounting-raw-image-files-and-kpartx/) - A good guide of using kpartx for other things
