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

# Installing

Executing the command

```sh
make Android-install
```

will install the app on your smartphone. Make sure it is connected to your PC and USB debugging is enabled!

# Using

For usage, see page 48 and following of the paper.

To get a certificate onto the smartphone, you may use the Makefile.

This will create the PKCS15 files on the smartphone:

```sh
make create-pkcs15-files
```

This will generate a 2048 Bit RSA Key on the smartphone:

```sh
make generate-key
```

This will show you the slot id, which you might need for the next step if it is not 01:

```sh
make show-slot-and-id
```

This will create a Certificate Signing Request. You may specify the information for the distinguished name and the slot, if necessary:

```sh
make create-csr
```

So in the end you could do something like:

```sh
make create-csr SLOT=02 COMMON_NAME="Erik Nellessen" EMAIL_ADDRESS=mysecretemail@doesnt.exist
```

You can have a look at the CSR by executing:
```sh
make show-csr
```

Now you need to sign the certificate signing request with a CA. The Makefile target creates a demo CA using openssl. After that, it signs the certificate. You may specify the path to your openssl.cnf in the OPENSSL_CONF environment variable.

```sh
make get-cert
```

The last step is to store the certificate on the smartphone:
```sh
make store-certificate
```

Now you can configure Thunderbird/Icedove as described in the paper on page 51 and start decrypting/signing e-mails!

Have fun!