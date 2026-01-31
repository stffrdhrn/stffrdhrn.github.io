---
title: OpenRISC Multicore - SMP Linux soft lockups
layout: post
date: 2026-01-13 18:11
categories: [ hardware, linux, embedded, openrisc ]
---

This is the story of figuring out why OpenRISC Linux was no longer booting on FPGA boards.

In July 2025 we received a bug report: [https://github.com/openrisc/mor1kx/issues/168](mor1kx pipeline is stuck in dualcore iverilog RTL simulation).

The report showed a hang on the second CPU of a custom
[multicore](https://en.wikipedia.org/wiki/Multi-core_processor) platform.  The
CPU cores that we use in FPGA based
[SoCs](https://en.wikipedia.org/wiki/System_on_a_chip) are highly configurable,
we can change cache sizes, MMU set sizes, memory synchronization strategies and
other settings.  The first steps were to ensure that these settings were
correct.   After some initial discussions and adjustments the user was able to
make progress, but Linux booted and hung with an error.  The following is a
snippet of the boot log:

```
[ 72.530000] Run /init as init process
[ 95.470000] rcu: INFO: rcu_sched detected stalls on CPUs/tasks:
[ 95.470000] rcu: (detected by 0, t=2102 jiffies, g=-1063, q=3 ncpus=2)
[ 95.470000] rcu: All QSes seen, last rcu_sched kthread activity 2088 (-20453--22541), jiffies_till_next_fqs=1, root ->qsmask 0x0
[ 95.470000] rcu: rcu_sched kthread timer wakeup didn't happen for 2087 jiffies! g-1063 f0x2 RCU_GP_WAIT_FQS(5) ->state=0x200
[ 95.470000] rcu: Possible timer handling issue on cpu=1 timer-softirq=194
[ 95.470000] rcu: rcu_sched kthread starved for 2088 jiffies! g-1063 f0x2 RCU_GP_WAIT_FQS(5) ->state=0x200 ->cpu=1
[ 95.470000] rcu: Unless rcu_sched kthread gets sufficient CPU time, OOM is now expected behavior.
[ 95.470000] rcu: RCU grace-period kthread stack dump:
[ 95.470000] task:rcu_sched state:R stack:0 pid:12 tgid:12 ppid:2 task_flags:0x208040 flags:0x00000000
[ 95.470000] Call trace:
[ 95.470000] [<(ptrval)>] 0xc00509a4
[ 95.470000] [<(ptrval)>] 0xc0050a04
```

In the above log we see an [RCU](https://docs.kernel.org/RCU/stallwarn.html)
stall warning indicating that CPU 1 running but not making progress on work it
has been assigned.  We can also see that the CPUs are both running but hanging.
It took until December 2025, 5 months, to locate and fix the bug.  In this
article we will discuss how we debugged and solved this issue.

# Reproducting the issue

To be honest I haven't run the OpenRISC multicore platform on a physical FGPA
development board for a few years, so just setting up the environemtn was going
to be a significant undertaking.

For the past few years I have been concentrating on OpenRISC software
so this meant using QEMU which is much more convenient.

To get the environment running we need a bunch of stuff:

 - De0 Nano Cyclone IV FPGA dev board with assortment of USB and serial device cables
 - [fusesoc](https://fusesoc.readthedocs.io/en/stable/) 2.4.3 - Tool for RTL
   packange mangement, building and device programming.
 - [Quartus Prime Design Software](https://www.altera.com/products/development-tools/quartus) 24.1 - for verilog synthesis and place and route
 - The fusesoc OpenRISC multicore SoC - https://github.com/stffrdhrn/de0_nano-multicore
 - [OpenOCD](https://openocd.org) 0.11.0 for debugging and loading software onto the board
 - The OpenRISC [toolchain](https://github.com/stffrdhrn/or1k-toolchain-build/releases) 15.1.0
 - Linux kernel source code
 - Old kernel patches to get OpenRISC running on the de0 nano
 - A busybox [rootfs](https://github.com/stffrdhrn/or1k-rootfs-build/releases) for userspace utilities

There is a lot of information about how to get your FPGA board working with
openrisc in our [De0 Nano tutorials](https://openrisc.io/tutorials/de0_nano/).
Please refer to the tutorials if you would like to follow up.

Some notes about getting the De0 Nano development environment up again:

 - OpenOCD versions after 0.11 no longer work with OpenRISC and it's
   adv_debug_sys debug interface.
   OpenOCD will connect over the USB Blaster JTAG connection but
   requests to write and read fail with CDC failures.
 - While debugging the OpenOCD issues I verified our simulated
   JTAG connectivity which uses OpenOCD to connect over
[jtag_vpi](https://github.com/fjullien/jtag_vpi) does still work.
 - Fusesoc is continuously evolving and the [de0_nano](https://github.com/olofk/de0_nano/commits/master/) and
   `de0_nano-multicore`.
   projects needed to be updated to get them working again.

Once the development board was loaded and running a simple hello world program
I could continue try to run Linux.

Once we got the environment up we needed to build and load the Linux kernel.
This requires a devicetree (DTS) file for our De0 Nano multicore board.

I was able to start with the existing simple-smp config.

```
make ARCH=openrisc CROSS_COMPILE=or1k-linux- | grep defconfig
  defconfig       - New config with default from ARCH supplied defconfig
  savedefconfig   - Save current config as ./defconfig (minimal config)
  alldefconfig    - New config with all symbols set to default
  olddefconfig    - Same as oldconfig but sets new symbols to their
  or1klitex_defconfig         - Build for or1klitex
  or1ksim_defconfig           - Build for or1ksim
  simple_smp_defconfig        - Build for simple_smp
  virt_defconfig              - Build for virt
make ARCH=openrisc CROSS_COMPILE=or1k-linux- simple_smp_defconfig
make
```

We then need to create a device tree that works for the de0 nano, it is also
almost the same as the simple SMP board but:

 - The FPGA SoC runs at 50Mhz instead of 20Mhz
 - The FPGA SoC has no ethernet
 - The FPGA SoC has gpios which we would like to wire up

The following is an example dts file.

```
// File: arch/openrisc/boot/dts/de0nano-smp.dts
#include <dt-bindings/gpio/gpio.h>
#include <dt-bindings/leds/common.h>

/dts-v1/;
/ {
	compatible = "opencores,or1ksim";
	#address-cells = <1>;
	#size-cells = <1>;
	interrupt-parent = <&pic>;

	aliases {
		uart0 = &serial0;
	};

	chosen {
		bootargs = "earlycon";
		stdout-path = "uart0:115200";
	};

	cpus {
		#address-cells = <1>;
		#size-cells = <0>;
		cpu0: cpu@0 {
			compatible = "opencores,or1200-rtlsvn481";
			reg = <0>;
			clock-frequency = <50000000>;
		};
		cpu1: cpu@1 {
			compatible = "opencores,or1200-rtlsvn481";
			reg = <1>;
			clock-frequency = <50000000>;
		};
	};

	leds {
		compatible = "gpio-leds";
		led-heartbeat {
			gpios = <&gpio0 0 GPIO_ACTIVE_HIGH>;
			color = <LED_COLOR_ID_GREEN>;
			function = LED_FUNCTION_HEARTBEAT;
			linux,default-trigger = "heartbeat";
			label = "heartbeat";
		};
	};

	pic: pic {
		compatible = "opencores,or1k-pic-level";
		#interrupt-cells = <1>;
		interrupt-controller;
	};

	memory@0 {
		device_type = "memory";
		reg = <0x00000000 0x02000000>;
	};

	serial0: serial@90000000 {
		compatible = "opencores,uart16550-rtlsvn105", "ns16550a";
		reg = <0x90000000 0x100>;
		interrupts = <2>;
		clock-frequency = <50000000>;
	};

	gpio0: gpio@91000000 {
		compatible = "brcm,bcm6345-gpio";
		reg = <0x91000000 0x1>, <0x91000001 0x1>;
		reg-names = "dat", "dirout";
		gpio-controller;
		#gpio-cells = <2>;
	};

	ompic: ompic@98000000 {
		compatible = "openrisc,ompic";
		reg = <0x98000000 16>;
		interrupt-controller;
		#interrupt-cells = <0>;
		interrupts = <1>;
	};
};
```

### Configuring the kernel

The default smp config does not have debugging configured.  Run `make ARCH=openrisc menuconfig`
and enable the following.

```
 Kernel hacking  --->
   printk and dmesg options  --->
    [*] Show timing information on printks              CONFIG_PRINTK_TIME=y
   Compile-time checks and compiler options  --->
        Debug information (Disable debug information)   CONFIG_DEBUG_INFO_DWARF_TOOLCHAIN_DEFAULT=y
    [*] Provide GDB scripts for kernel debugging        CONFIG_GDB_SCRIPTS=y

 General setup  --->
   [*] Configure standard kernel features (expert users)  --->
     [*]   Load all symbols for debugging/ksymoops      CONFIG_KALLSYMS=y
```

The `CONFIG_KALLSYMS` seems conspicuous, but it is one of the most important config switches
to enable.  This enables our stack traces to show symbol information, which makes it easier to understand
where our crashes happen.


With all of that configured we can build the kernel.

```
make -j12 \
  ARCH=openrisc \
  CROSS_COMPILE=or1k-linux- \
  CONFIG_INITRAMFS_SOURCE="$HOME/work/openrisc/busybox-rootfs/initramfs $HOME/work/openrisc/busybox-rootfs/initramfs.devnodes" \
  CONFIG_BUILTIN_DTB_NAME="de0nano-smp"
```

After this we will have our `elf` binary at `vmlinux` ready to load onto our board.

Loading it using the gdb and openocd commands from the tutorial
we could see the system boot and then hang with the following:

```
[   29.070000] This architecture does not have kernel memory protection.
[   29.080000] Run /init as init process
[   56.140000] watchdog: BUG: soft lockup - CPU#0 stuck for 22s! [init:1]
[   56.140000] CPU: 0 UID: 0 PID: 1 Comm: init Not tainted 6.17.0-rc5-simple-smp-00004-g8c30b0018f9d-dirty #208 NONE
[   56.140000] CPU #: 0
[   56.140000]    PC: c00de9b8    SR: 0000807f    SP: c102dd04
[   56.140000] GPR00: 00000000 GPR01: c102dd04 GPR02: c102dd60 GPR03: 00000006
[   56.140000] GPR04: c1ff8aa0 GPR05: c1ff8aa0 GPR06: 00000000 GPR07: 00000000
[   56.140000] GPR08: 00000002 GPR09: c00decd0 GPR10: c102c000 GPR11: 00000006
[   56.140000] GPR12: ffffffff GPR13: 00000002 GPR14: c102de54 GPR15: c098b9d0
[   56.140000] GPR16: c1fc59e0 GPR17: 00000001 GPR18: c1ff8aa0 GPR19: c1fcfd20
[   56.140000] GPR20: 00000001 GPR21: ffffffff GPR22: 00000001 GPR23: 00000002
[   56.140000] GPR24: c0013a70 GPR25: 00000000 GPR26: 00000001 GPR27: 00000000
[   56.140000] GPR28: 01644000 GPR29: 0000000b GPR30: 0000000b GPR31: 00000002
[   56.140000]   RES: 00000006 oGPR11: ffffffff
[   56.140000] Process init (pid: 1, stackpage=c103c000)
[   56.140000]
[   56.140000] Stack:
[   56.140000] Call trace:
[   56.140000] [<(ptrval)>] smp_call_function_many_cond+0x4d4/0x5b0
[   56.140000] [<(ptrval)>] ? _raw_spin_unlock_irqrestore+0x28/0x38
[   56.140000] [<(ptrval)>] on_each_cpu_cond_mask+0x28/0x38
[   56.140000] [<(ptrval)>] smp_icache_page_inv+0x30/0x40
[   56.140000] [<(ptrval)>] update_cache+0x12c/0x160
[   56.140000] [<(ptrval)>] set_pte_range+0xd4/0x170
[   56.140000] [<(ptrval)>] filemap_map_pages+0x20c/0x708
[   56.140000] [<(ptrval)>] handle_mm_fault+0xee4/0x1c74
[   56.140000] [<(ptrval)>] ? __schedule+0x2dc/0x788
[   56.140000] [<(ptrval)>] ? _raw_spin_lock_irqsave+0x28/0x98
[   56.140000] [<(ptrval)>] ? _raw_spin_unlock_irqrestore+0x28/0x38
[   56.140000] [<(ptrval)>] do_page_fault+0x1d0/0x4b4
[   56.140000] [<(ptrval)>] _data_page_fault_handler+0x104/0x10c
[   56.140000]
[   56.140000]  c102dce4:       00000000
[   56.140000]  c102dce8:       00000000
[   56.140000]  c102dcec:       00000000
[   56.140000]  c102dcf0:       00000000
[   56.140000]  c102dcf4:       c102dd04
```

CORE 0 has issued a IPI and is waiting for CORE 1 to finish the IPI.

```
// kernel/smp.c:872
        if (run_remote && wait) {
                for_each_cpu(cpu, cfd->cpumask) {
                        call_single_data_t *csd;

                        csd = per_cpu_ptr(cfd->csd, cpu);
                        csd_lock_wait(csd);    <--- maybe here?
                }
        }
```

When debugging if I read the lock it appears to be unlocked.  So I suspect an issue
with the memory bus synchronization!

```
[   68.220000] watchdog: BUG: soft lockup - CPU#1 stuck for 22s! [kcompactd0:30]
[   68.220000] CPU: 1 UID: 0 PID: 30 Comm: kcompactd0 Tainted: G             L      6.17.0-rc5-simple-smp-00004-g8c30b0018f9d-dirty #208 NONE
[   68.220000] Tainted: [L]=SOFTLOCKUP
[   68.220000] CPU #: 1
[   68.220000]    PC: c052b5dc    SR: 0000827f    SP: c10c3b5c
[   68.220000] GPR00: 00000000 GPR01: c10c3b5c GPR02: c10c3b64 GPR03: c11d203c
[   68.220000] GPR04: c11d40c0 GPR05: 300e8000 GPR06: c10c3b68 GPR07: c10c3b64
[   68.220000] GPR08: 00000000 GPR09: c013ad00 GPR10: c10c2000 GPR11: c11de1d0
[   68.220000] GPR12: 00000000 GPR13: 0002003d GPR14: c1fe47a4 GPR15: 00000000
[   68.220000] GPR16: c10c3b98 GPR17: 00000011 GPR18: 300ea000 GPR19: 00000012
[   68.220000] GPR20: ff000000 GPR21: 00130011 GPR22: 01000000 GPR23: c0993f8c
[   68.220000] GPR24: c11d2000 GPR25: c10c3dd8 GPR26: 00000000 GPR27: c1ff31e4
[   68.220000] GPR28: c1155c6c GPR29: 00000000 GPR30: c11d2000 GPR31: 00000002
[   68.220000]   RES: c11de1d0 oGPR11: ffffffff
[   68.220000] Process kcompactd0 (pid: 30, stackpage=c10ad300)
[   68.220000]
[   68.220000] Stack:
[   68.220000] Call trace:
[   68.220000] [<(ptrval)>] page_vma_mapped_walk+0x250/0x434
[   68.220000] [<(ptrval)>] try_to_migrate_one+0xe4/0x430
[   68.220000] [<(ptrval)>] __rmap_walk_file+0x110/0x208
[   68.220000] [<(ptrval)>] ? compaction_free+0x0/0xfc
[   68.230000] [<(ptrval)>] rmap_walk+0x80/0x98
[   68.230000] [<(ptrval)>] try_to_migrate+0x84/0xe8
[   68.230000] [<(ptrval)>] ? try_to_migrate_one+0x0/0x430
[   68.230000] [<(ptrval)>] ? folio_not_mapped+0x0/0x70
[   68.230000] [<(ptrval)>] ? folio_lock_anon_vma_read+0x0/0x2c4
[   68.230000] [<(ptrval)>] migrate_pages_batch+0x2d0/0xea8
[   68.230000] [<(ptrval)>] ? compaction_alloc+0x0/0xca4
[   68.230000] [<(ptrval)>] migrate_pages+0x1c0/0x5bc
[   68.230000] [<(ptrval)>] ? compaction_free+0x0/0xfc
[   68.230000] [<(ptrval)>] ? compaction_alloc+0x0/0xca4
[   68.230000] [<(ptrval)>] compact_zone+0x82c/0xce0
[   68.230000] [<(ptrval)>] ? compaction_free+0x0/0xfc
[   68.240000] [<(ptrval)>] compact_node+0x9c/0xe0
[   68.240000] [<(ptrval)>] kcompactd+0x184/0x398
[   68.240000] [<(ptrval)>] ? autoremove_wake_function+0x0/0x58
[   68.240000] [<(ptrval)>] ? kcompactd+0x0/0x398
[   68.240000] [<(ptrval)>] kthread+0x120/0x270
[   68.240000] [<(ptrval)>] ? _raw_spin_lock_irq+0x20/0x90
[   68.240000] [<(ptrval)>] ? kthread+0x0/0x270
[   68.240000] [<(ptrval)>] ret_from_fork+0x1c/0x80
[   68.240000]
[   68.240000]  c10c3b3c:       00000000
```

The system runs for a while and can execute commands, 2 CPU's are reported online
but after some time we get the following lockup and the system stops.

```
[  410.790000] rcu: INFO: rcu_sched self-detected stall on CPU
[  410.790000] rcu:     0-...!: (2099 ticks this GP) idle=4f64/1/0x40000002 softirq=438/438 fqs=277
[  410.790000] rcu:     (t=2100 jiffies g=-387 q=1845 ncpus=2)
[  410.790000] rcu: rcu_sched kthread starved for 1544 jiffies! g-387 f0x0 RCU_GP_WAIT_FQS(5) ->state=0x0 ->cpu=1
[  410.790000] rcu:     Unless rcu_sched kthread gets sufficient CPU time, OOM is now expected behavior.
[  410.790000] rcu: RCU grace-period kthread stack dump:
[  410.790000] task:rcu_sched       state:R  running task     stack:0     pid:13    tgid:13    ppid:2      task_flags:0x208040 flags:0x00000000
[  410.850000] Call trace:
[  410.850000] [<(ptrval)>] sched_show_task.part.0+0x104/0x138
[  410.850000] [<(ptrval)>] sched_show_task+0x2c/0x3c
[  410.850000] [<(ptrval)>] rcu_check_gp_kthread_starvation+0x144/0x1e4
[  410.850000] [<(ptrval)>] rcu_sched_clock_irq+0xd00/0xe9c
[  410.850000] [<(ptrval)>] ? ipi_icache_page_inv+0x0/0x24
[  410.850000] [<(ptrval)>] update_process_times+0xa8/0x128
[  410.850000] [<(ptrval)>] tick_nohz_handler+0xd8/0x264
[  410.900000] [<(ptrval)>] ? tick_program_event+0x78/0x100
[  410.900000] [<(ptrval)>] tick_nohz_lowres_handler+0x54/0x80
[  410.900000] [<(ptrval)>] timer_interrupt+0x88/0xc8
[  410.900000] [<(ptrval)>] _timer_handler+0x84/0x8c
[  410.900000] [<(ptrval)>] ? smp_call_function_many_cond+0x4d4/0x5b0
[  410.900000] [<(ptrval)>] ? ipi_icache_page_inv+0x0/0x24
[  410.900000] [<(ptrval)>] ? smp_call_function_many_cond+0x1bc/0x5b0
[  410.950000] [<(ptrval)>] ? __alloc_frozen_pages_noprof+0x118/0xde8
[  410.950000] [<(ptrval)>] ? ipi_icache_page_inv+0x14/0x24
[  410.950000] [<(ptrval)>] ? smp_call_function_many_cond+0x4d4/0x5b0
[  410.950000] [<(ptrval)>] on_each_cpu_cond_mask+0x28/0x38
[  410.950000] [<(ptrval)>] smp_icache_page_inv+0x30/0x40
[  410.950000] [<(ptrval)>] update_cache+0x12c/0x160
[  410.950000] [<(ptrval)>] handle_mm_fault+0xc48/0x1cc0
[  410.950000] [<(ptrval)>] ? _raw_spin_unlock_irqrestore+0x28/0x38
[  411.000000] [<(ptrval)>] do_page_fault+0x1d0/0x4b4
[  411.000000] [<(ptrval)>] ? sys_setpgid+0xe4/0x1f8
[  411.000000] [<(ptrval)>] ? _data_page_fault_handler+0x104/0x10c
[  411.000000] rcu: Stack dump where RCU GP kthread last ran:
[  411.000000] Task dump for CPU 1:
[  411.000000] task:kcompactd0      state:R  running task     stack:0     pid:29    tgid:29    ppid:2      task_flags:0x218040 flags:0x00000008
[  411.000000] Call trace:
[  411.050000] [<(ptrval)>] sched_show_task.part.0+0x104/0x138
[  411.050000] [<(ptrval)>] dump_cpu_task+0xd8/0xe0
[  411.050000] [<(ptrval)>] rcu_check_gp_kthread_starvation+0x1bc/0x1e4
[  411.050000] [<(ptrval)>] rcu_sched_clock_irq+0xd00/0xe9c
[  411.050000] [<(ptrval)>] ? ipi_icache_page_inv+0x0/0x24
[  411.050000] [<(ptrval)>] update_process_times+0xa8/0x128
[  411.050000] [<(ptrval)>] tick_nohz_handler+0xd8/0x264
[  411.050000] [<(ptrval)>] ? tick_program_event+0x78/0x100
[  411.100000] [<(ptrval)>] tick_nohz_lowres_handler+0x54/0x80
[  411.100000] [<(ptrval)>] timer_interrupt+0x88/0xc8
[  411.100000] [<(ptrval)>] _timer_handler+0x84/0x8c
[  411.100000] [<(ptrval)>] ? smp_call_function_many_cond+0x4d4/0x5b0
[  411.100000] [<(ptrval)>] ? ipi_icache_page_inv+0x0/0x24
[  411.100000] [<(ptrval)>] ? smp_call_function_many_cond+0x1bc/0x5b0
[  411.100000] [<(ptrval)>] ? __alloc_frozen_pages_noprof+0x118/0xde8
[  411.150000] [<(ptrval)>] ? ipi_icache_page_inv+0x14/0x24
[  411.150000] [<(ptrval)>] ? smp_call_function_many_cond+0x4d4/0x5b0
[  411.150000] [<(ptrval)>] on_each_cpu_cond_mask+0x28/0x38
[  411.150000] [<(ptrval)>] smp_icache_page_inv+0x30/0x40
[  411.150000] [<(ptrval)>] update_cache+0x12c/0x160
[  411.150000] [<(ptrval)>] handle_mm_fault+0xc48/0x1cc0
[  411.150000] [<(ptrval)>] ? _raw_spin_unlock_irqrestore+0x28/0x38
[  411.150000] [<(ptrval)>] do_page_fault+0x1d0/0x4b4
[  411.200000] [<(ptrval)>] ? sys_setpgid+0xe4/0x1f8
[  411.200000] [<(ptrval)>] ? _data_page_fault_handler+0x104/0x10c
[  411.200000] CPU: 0 UID: 0 PID: 61 Comm: sh Not tainted 6.19.0-rc5-simple-smp-00005-g4c0503f58a74 #339 NONE
[  411.200000] CPU #: 0
[  411.200000]    PC: c00e9dc4    SR: 0000807f    SP: c1235da4
[  411.200000] GPR00: 00000000 GPR01: c1235da4 GPR02: c1235e00 GPR03: 00000006
[  411.200000] GPR04: c1fe3ae0 GPR05: c1fe3ae0 GPR06: 00000000 GPR07: 00000000
[  411.200000] GPR08: 00000002 GPR09: c00ea0dc GPR10: c1234000 GPR11: 00000006
[  411.200000] GPR12: ffffffff GPR13: 00000002 GPR14: 300ef234 GPR15: c09b7b20
[  411.200000] GPR16: c1fc1b30 GPR17: 00000001 GPR18: c1fe3ae0 GPR19: c1fcffe0
[  411.200000] GPR20: 00000001 GPR21: ffffffff GPR22: 00000001 GPR23: 00000002
[  411.200000] GPR24: c0013950 GPR25: 00000000 GPR26: 00000001 GPR27: 00000000
[  411.200000] GPR28: 01616000 GPR29: 0000000b GPR30: 00000001 GPR31: 00000002
[  411.200000]   RES: 00000006 oGPR11: ffffffff
[  411.200000] Process sh (pid: 61, stackpage=c12457c0)
[  411.200000]
[  411.200000] Stack:
[  411.200000] Call trace:
[  411.200000] [<(ptrval)>] smp_call_function_many_cond+0x4d4/0x5b0
[  411.200000] [<(ptrval)>] on_each_cpu_cond_mask+0x28/0x38
[  411.200000] [<(ptrval)>] smp_icache_page_inv+0x30/0x40
[  411.200000] [<(ptrval)>] update_cache+0x12c/0x160
[  411.200000] [<(ptrval)>] handle_mm_fault+0xc48/0x1cc0
[  411.200000] [<(ptrval)>] ? _raw_spin_unlock_irqrestore+0x28/0x38
[  411.200000] [<(ptrval)>] do_page_fault+0x1d0/0x4b4
[  411.200000] [<(ptrval)>] ? sys_setpgid+0xe4/0x1f8
[  411.200000] [<(ptrval)>] ? _data_page_fault_handler+0x104/0x10c
[  411.200000]
[  411.200000]  c1235d84:       0000001c
[  411.200000]  c1235d88:       00000074
[  411.200000]  c1235d8c:       c1fc0008
[  411.200000]  c1235d90:       00000000
[  411.200000]  c1235d94:       c1235da4
[  411.200000]  c1235d98:       c0013964
[  411.200000]  c1235d9c:       c1235e00
[  411.200000]  c1235da0:       c00ea0dc
[  411.200000] (c1235da4:)      00000006

```

From the trace we can see both CPU's are in similar code locations.

 - CPU0 : is in `smp_icache_page_inv -> on_each_cpu_cond_mask -> smp_call_function_many_cond`
 - CPU1 : is in `smp_icache_page_inv -> on_each_cpu_cond_mask`

CPU1 is additionally handling a timer which is reporting the RCU stall, we can
ignore those bits of the stack, as it is the just reporting the problem for us it is not the root cause.  So what is happening?

Let's try to understand what is happening.
The `smp_icache_page_inv` function is called to invalidate an icache page, it will force all CPU's to invalidate a cache entry by scheduling
each CPU to call a cache invalidation function.  This is scheduled with the `smp_call_function_many_cond` call.

On `CPU0` and `CPU1` this is being initiated by a page fault as we see `do_page_fault` at the bottom of the stack.
The do_page_fault function will be called when the CPU handles a TLB miss exception or if there was a page fault.
This must mean that a executable page was not available in memory and caused a fault, once the page
was mapped the icache needs to be invalidated.

This call ends up:

 1. Creating a function call entry
 2. Adding the function call entry to a queue
 3. Raising an IPI
 4. Waiting for the IPI to finish

Blah Blah, why is it hung?  If we open up the debugger we can see, we are stuck here:

```
#0  0xc00ea11c in csd_lock_wait (csd=0xc1fd0000) at kernel/smp.c:351
351             smp_cond_load_acquire(&csd->node.u_flags, !(VAL & CSD_FLAG_LOCK));
```

The `smp_cond_load_acquire` function calls:

```
349     static __always_inline void csd_lock_wait(call_single_data_t *csd)
350     {
351             smp_cond_load_acquire(&csd->node.u_flags, !(VAL & CSD_FLAG_LOCK));
352     }
353     #endif
```

### include/linux/smp_types.h

```
enum {
        CSD_FLAG_LOCK           = 0x01,

```

The `smp_cond_load_acquire` macro is just a loop waiting for `&csd->node.u_flags`
the 1 bit `CSD_FLAG_LOCK` to be cleared.

If we check the value of the `u_flags`:

```
(gdb) p/x csd->node.u_flags
$14 = 0x86330004
```

What is this we see?  The value is `0x86330004`, but that means the `0x1` bit is not set.
It should be exiting the loop.

At this point I thought, perhaps this is a hardware issue.  The value in memory does not
match the value the CPU is reading.  Is the a memory synchonization issue?  Does the CPU cache
incorrectly have the locked flag?

# It's a hardware issue

Using the debu

## Gemini to the rescue

Nope, they kept chasing red herrings.

## Using signal tap to really see what was in memory!

# Actually, its a Kernel issue

# The Fix

# Followups

 - Tutorials and Upstreaming patches
 - OpenOCD is currently broken for OpenRISC
 - OpenOCD doesnt support multicore
 - OpenOCD / GDB bugs
