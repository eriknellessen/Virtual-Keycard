SHELL=/bin/bash
PREFIX= $(shell pwd)
OPENPACE=$(PREFIX)/openpace
VSMARTCARD=$(PREFIX)/vsmartcard
C_INCLUDE_PATH=$(PREFIX)/include

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
	autoreconf --verbose --install ;\
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
	gcc -shared -fPIC -DPIC -o .libs/card-virtualkeycard.so .libs/muscle.o .libs/muscle-filesystem.o .libs/card.o .libs/sc.o .libs/card-virtualkeycard.o -Wl,-rpath -Wl,$(PREFIX)/lib -L$(PREFIX)/lib/ -L$(PREFIX)/lib64/ -lnpa -lcrypto -lopensc
	
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
	ant debug ;\
	$(ANDROID_SDK_ROOT)/platform-tools/adb install -r bin/MainActivity-debug.apk
	