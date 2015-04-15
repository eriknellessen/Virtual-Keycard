# Welcome

This software system allows you to decrypt and sign your e-mails with your smartphone instead of using a contactless smartcard. The smartphone communicates with your PC via NFC (as a contactless smartcard would).

The paper can be found here:
http://sar.informatik.hu-berlin.de/research/publications/SAR-PR-2014-08/SAR-PR-2014-08_.pdf

Warning: This is just proof-of-concept code and should _NOT_ be used in
production environments

# Tested platforms:

* Android 4.4 Kitkat on Nexus 5
* Android 4.4 Kitkat on LG G2 Mini

The Android app only works on Android 4.4 Kitkat and higher.

# Building

To create this app, eclipse was used.

To use the app, build it using the makefile in the following way:

```sh
make ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT
```

I used adt-bundle-linux-x86-20131030 as SDK. The OS on which I build the app is Debian Jessie (32 Bit).

Afterwards, the Makefile also installs the app on your smartphone. Make sure it is connected to your PC and USB debugging is enabled!

Have fun!