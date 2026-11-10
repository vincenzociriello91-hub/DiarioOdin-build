### AnyKernel3 Ramdisk Mod Script
## osm0sis @ xda-developers

### AnyKernel setup
# global properties
properties() { '
kernel.string=Kernel su misura per AYN Odin 2 Portal, LineageOS 24.0 (futex_waitv, BBR)
do.devicecheck=0
do.modules=0
do.systemless=0
do.cleanup=1
do.cleanuponabort=0
device.name1=
supported.versions=
supported.patchlevels=
supported.vendorpatchlevels=
'; } # end properties

### AnyKernel install
## boot shell variables
block=boot
is_slot_device=1
ramdisk_compression=auto
patch_vbmeta_flag=auto

# import functions/variables and setup patching - see for reference (DO NOT REMOVE)
. tools/ak3-core.sh

# Sicurezza: questo kernel funziona solo con i moduli della ROM per cui è stato compilato.
# Si installa dal sistema acceso (Kernel Flasher), quindi "uname -r" è il kernel in uso.
atteso="@KERNEL_RELEASE@"
[ "$(uname -r)" = "$atteso" ] || abort "Questo kernel vale solo per $atteso; qui c'e' $(uname -r). Non e' stato cambiato nulla."

# boot install: sostituisce solo il kernel, il resto della partizione resta com'è
split_boot
flash_boot
## end boot install
