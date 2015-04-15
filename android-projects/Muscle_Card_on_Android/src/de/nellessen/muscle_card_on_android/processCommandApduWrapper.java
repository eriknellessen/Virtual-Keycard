package de.nellessen.muscle_card_on_android;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

public class processCommandApduWrapper extends HostApduService {
	MSCHostApduService MyHostApduService;
	
	public processCommandApduWrapper(){
		this.MyHostApduService = MSCHostApduService.getInstance();
	}

	@Override
	public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
		return MyHostApduService.processCommandApdu(apdu, null);
	}
	
	@Override
	public void onDeactivated(int reason) {

	}
}
