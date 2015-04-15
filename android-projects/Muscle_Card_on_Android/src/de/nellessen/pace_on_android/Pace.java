package de.nellessen.pace_on_android;

import com.npa.androsmex.asn1.SecurityInfos;
import com.npa.androsmex.iso7816.CommandAPDU;
import com.npa.androsmex.iso7816.ResponseAPDU;
import com.npa.androsmex.pace.PaceOperator;

public class Pace {

	PaceOperator po;

	/* Code taken or inspired from Ole Richter's bachelor thesis 
	 * "Prüfung von öffentlichen eID-Terminals mit einem 
	 * Android-Smartphone" */
	public Pace(String pin) {
		po = new PaceOperator(null);

		String ef_cardaccess_string = "3181c6300d060804007f00070202020201023012060a04007f000702020302020201020201013012060a04007f0007020204020202010202010D301c060904007f000702020302300c060704007f0007010202010D020101302f060804007f0007020206162368747470733a2f2f7777772e686a702d636f6e73756c74696e672e636f6d2f686f6d65303e060804007f000702020831323012060a04007f00070202030202020102020102301c060904007f000702020302300c060704007f0007010202010D020102";
		SecurityInfos si = po.getSecurityInfosFromCardAccess(ef_cardaccess_string);

		po.setAuthTemplate(si.getPaceInfoList().get(0));
		po.initialize(pin);
	}

	/* Code taken or inspired from Ole Richter's bachelor thesis 
	 * "Prüfung von öffentlichen eID-Terminals mit einem 
	 * Android-Smartphone" */
	public byte[] performPace(byte[] apdu) {
		byte[] data = po.performPace(apdu);
		byte[] responseApdu = new byte[data.length + 2];
		byte[] sw = { (byte) 0x90, (byte) 0x00 };
		System.arraycopy(data, 0, responseApdu, 0, data.length);
		System.arraycopy(sw, 0, responseApdu, data.length, sw.length);

		return responseApdu;
	}

	// Decrypt an apdu, which came from openpace
	public byte[] decryptCommandApdu(byte[] apdu) {
		CommandAPDU cmdApdu = new CommandAPDU(apdu);
		byte[] returnValue = null;
		try {
			returnValue = this.po.getSMObject().unwrap_capdu(cmdApdu).getBytes();
			returnValue[0] = (byte) 0xB0;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return returnValue;
	}

	// Encrypt an apdu, which goes to openpace
	public byte[] encryptResponseApdu(byte[] apdu) {
		ResponseAPDU rApdu = new ResponseAPDU(apdu);
		byte[] returnValue = null;
		try {
			returnValue = this.po.getSMObject().wrap_rapdu(rApdu).getBytes();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return returnValue;
	}
	
	public boolean existsSecureMessagingObject(){
		if(this.po.getSMObject() != null)
			return true;
		return false;
	}
}
