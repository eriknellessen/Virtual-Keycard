SHELL=/bin/bash
PREFIX= $(shell pwd)
OPENPACE=$(PREFIX)/openpace
VSMARTCARD=$(PREFIX)/vsmartcard
C_INCLUDE_PATH=$(PREFIX)/include
LD_LIBRARY_PATH=$(PREFIX)/lib:$(PREFIX)/lib64
ENGINE_PKCS11=$(shell find / -iname engine_pkcs11.so 2>/dev/null | head -1)
OPENSC_PKCS11=$(shell find $(PREFIX) -iname opensc-pkcs11.so 2>/dev/null | head -1)
KEY_ID=31337
SLOT?=01
CERT?=crt_for_vk.crt
COUNTRY?=DE
STATE?=Berlin
LOCATION?=Berlin
ORGANIZATION?=Noname Corp.
ORGANIZATIONAL_UNIT?=Noop Unit
COMMON_NAME?=Anonymous
EMAIL_ADDRESS?=Anonymous@noname-corp.com
OPENSSL_CONF?=/etc/ssl/openssl.cnf

all: virtualkeycard-OpenSC virtualkeycard-Android

#See http://frankmorgner.github.io/vsmartcard/npa/README.html
OpenPACE:
	git clone https://github.com/frankmorgner/openpace.git $(OPENPACE) ;\
	cd $(OPENPACE) ;\
	autoreconf --verbose --install ;\
	# with `--enable-openssl-install` OpenSSL will be downloaded and installed along with OpenPACE \
	./configure --enable-openssl-install --prefix=$(PREFIX) ;\
	make install && cd -
	
#See http://frankmorgner.github.io/vsmartcard/npa/README.html
OpenSC:
	git clone https://github.com/frankmorgner/vsmartcard.git $(VSMARTCARD)
	cd $(VSMARTCARD) ;\
	git submodule init ;\
	git submodule update ;\
	cd $(VSMARTCARD)/npa/src/opensc ;\
	git checkout d72324ddf58f594a00c6b56b6abb8c4937b0794e ;\
	autoreconf --verbose --install ;\
	export LD_LIBRARY_PATH=$(LD_LIBRARY_PATH) ;\
	# adding PKG_CONFIG_PATH here lets OpenSC use the patched OpenSSL \
	./configure --prefix=$(PREFIX) PKG_CONFIG_PATH=$(PREFIX)/lib/pkgconfig:$(PREFIX)/lib64/pkgconfig --enable-sm ;\
	export C_INCLUDE_PATH=$(C_INCLUDE_PATH) ;\
	make install && cd -

#See http://frankmorgner.github.io/vsmartcard/npa/README.html
libnpa:
	cd $(VSMARTCARD)/npa ;\
	autoreconf --verbose --install ;\
	./configure --prefix=$(PREFIX) PKG_CONFIG_PATH=$(PREFIX)/lib/pkgconfig:$(PREFIX)/lib64/pkgconfig OPENSC_LIBS="-L$(PREFIX)/lib -L$(PREFIX)/lib64 -lopensc -lcrypto" ;\
	make install && cd -
	
Dependencies: OpenPACE OpenSC libnpa

virtualkeycard-OpenSC: Dependencies
	cp opensc-files/card-virtualkeycard.c $(PREFIX)/vsmartcard/npa/src/opensc/src/libopensc/ || echo "Never mind."
	cp opensc-files/pkcs15-virtualkeycard.c $(PREFIX)/vsmartcard/npa/src/opensc/src/pkcs15init/ || echo "Never mind."
	cp opensc-files/virtualkeycard.profile $(PREFIX)/share/opensc/ || echo "Never mind."
	
	export C_INCLUDE_PATH=$(C_INCLUDE_PATH)
	
	#Build driver "virtualkeycard":
	cd $(PREFIX)/vsmartcard/npa/src/opensc/src/libopensc/ ;\
	gcc -DHAVE_CONFIG_H -I . -I ../.. -DOPENSC_CONF_PATH=\"$(PREFIX)/etc/opensc/opensc.conf\" -I ../../src -I$(PREFIX)/include -pthread -I/usr/include/PCSC -fno-strict-aliasing -g -O2 -Wall -Wextra -Wno-unused-parameter -Werror=declaration-after-statement -MT card-virtualkeycard.lo -MD -MP -MF .deps/card-virtualkeycard.Tpo -c card-virtualkeycard.c -fPIC -DPIC -o .libs/card-virtualkeycard.o ;\
	gcc -shared -fPIC -DPIC -o .libs/card-virtualkeycard.so .libs/muscle.o .libs/muscle-filesystem.o .libs/card.o .libs/sc.o .libs/card-virtualkeycard.o -Wl,-rpath -Wl,$(PREFIX)/lib -Wl,-rpath -Wl,$(PREFIX)/lib64 -L$(PREFIX)/lib/ -L$(PREFIX)/lib64/ -lnpa -lcrypto -lopensc
	
	#Build PKCS15-driver for virtualkeycard:
	cd $(PREFIX)/vsmartcard/npa/src/opensc/src/pkcs15init/ ;\
	gcc -DHAVE_CONFIG_H -I . -I ../.. -DSC_PKCS15_PROFILE_DIRECTORY=\"$(PREFIX)/share/opensc/\" -I ../../src -I$(PREFIX)/include -fno-strict-aliasing -g -O2 -Wall -Wextra -Wno-unused-parameter -Werror=declaration-after-statement -MT pkcs15-virtualkeycard.lo -MD -MP -MF .deps/pkcs15-virtualkeycard.Tpo -c pkcs15-virtualkeycard.c -fPIC -DPIC -o .libs/pkcs15-virtualkeycard.o ;\
	gcc -shared -fPIC -DPIC -o .libs/pkcs15-virtualkeycard.so .libs/pkcs15-virtualkeycard.o .libs/profile.o ../common/compat_strlcpy.o

	#Expand path in patch
	#Choosing other delimiters, %, for sed, so we do not have to mask the slashes in $(PREFIX)
	sed -i 's%$$(PREFIX)%$(PREFIX)%g' opensc-files/opensc.conf.patch
	cd $(PREFIX)/etc/ ;\
	patch < ../opensc-files/opensc.conf.patch
	
virtualkeycard-Android:
	git clone https://github.com/olerichter00/npa-emulator/ android-projects/npa-emulator
	sed -i 's/npa.setSMObject(sm);/if (npa != null) npa.setSMObject(sm);/g' android-projects/npa-emulator/src/com/npa/androsmex/pace/PaceOperator.java
	echo "android.library=true" >> android-projects/npa-emulator/project.properties
	$(ANDROID_SDK_ROOT)/tools/android update project --path android-projects/Virtual_Keycard/
	$(ANDROID_SDK_ROOT)/tools/android update project --path android-projects/Muscle_Card_on_Android/
	$(ANDROID_SDK_ROOT)/tools/android update project --path android-projects/npa-emulator/
	rm -rf android-projects/npa-emulator/bin/res/crunch/
	cd android-projects/Virtual_Keycard ;\
	cp libs/android-support-v4.jar ../npa-emulator/libs/ ;\
	ant clean ;\
	ant debug

Android-install:
	$(ANDROID_SDK_ROOT)/platform-tools/adb install -r $(PREFIX)/android-projects/Virtual_Keycard/bin/MainActivity-debug.apk

create-pkcs15-files:
	export LD_LIBRARY_PATH=$(LD_LIBRARY_PATH)
	$(PREFIX)/bin/pkcs15-init --create-pkcs15 --card-profile virtualkeycard

generate-key:
	export LD_LIBRARY_PATH=$(LD_LIBRARY_PATH)
	$(PREFIX)/bin/pkcs15-init --generate-key rsa/2048 --auth-id ff --key-usage sign,decrypt --id $(KEY_ID)

show-slot-and-id:
	export LD_LIBRARY_PATH=$(LD_LIBRARY_PATH)
	$(PREFIX)/bin/pkcs11-tool --module $(OPENSC_PKCS11) -L -O
	
create-csr:
	export LD_LIBRARY_PATH=$(LD_LIBRARY_PATH)
	echo "engine -t dynamic -pre SO_PATH:$(ENGINE_PKCS11) -pre ID:pkcs11 -pre LIST_ADD:1 -pre LOAD -pre MODULE_PATH:$(OPENSC_PKCS11)" > openssl_input
	echo "req -engine pkcs11 -new -key slot_$(SLOT)-id_$(KEY_ID) -keyform engine -out csr_from_vk.csr -sha256 -subj \"/C=$(COUNTRY)/ST=$(STATE)/L=$(LOCATION)/O=$(ORGANIZATION)/OU=$(ORGANIZATIONAL_UNIT)/CN=$(COMMON_NAME)/emailAddress=$(EMAIL_ADDRESS)\"" >> openssl_input
	openssl < openssl_input
	#rm openssl_input

show-csr:
	openssl req -text -in csr_from_vk.csr

get-cert:
	openssl genrsa -out ca-key.pem 4096
	openssl req -x509 -new -nodes -extensions v3_ca -key ca-key.pem -days 1024 -out ca-root.pem -sha512
	ln -s . demoCA
	ln -s . newcerts
	touch index.txt
	echo "0000" > serial
	openssl ca -in csr_from_vk.csr -out crt_for_vk.crt -keyfile ca-key.pem -cert ca-root.pem -config $(OPENSSL_CONF)

store-certificate:
	export LD_LIBRARY_PATH=$(LD_LIBRARY_PATH)
	$(PREFIX)/bin/pkcs15-init --store-certificate $(CERT) --auth-id ff --id $(KEY_ID)