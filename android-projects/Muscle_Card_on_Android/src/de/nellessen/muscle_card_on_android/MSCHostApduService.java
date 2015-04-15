package de.nellessen.muscle_card_on_android;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

import javacard.framework.AID;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;

import android.content.Context;
import android.os.Bundle;

import com.licel.jcardsim.io.CAD;
import com.licel.jcardsim.io.JavaxSmartCardInterface;
import com.musclecard.CardEdge.CardEdge;

import de.nellessen.pace_on_android.Pace;

public class MSCHostApduService{
	
	Context context;
	JavaxSmartCardInterface simulator;
	private String modus;
	Pace pace;
	String defaultPin = "123456";
	int paceStep = 0;
	boolean lastLoginTry = false;

//-----------Initializing the objects----------	
	
	// Singleton stuff. Code inspired by
	// http://de.wikibooks.org/wiki/Java_Standard:_Muster_Singleton
	// Eine (versteckte) Klassenvariable vom Typ der eigenen Klasse
	private static MSCHostApduService instance;

	// Verhindere die Erzeugung des Objektes über andere Methoden
	private MSCHostApduService(Context appContext) {
		System.setProperty("com.licel.jcardsim.terminal.type", "2");
		CAD cad = new CAD(System.getProperties());
		simulator = (JavaxSmartCardInterface) cad.getCardInterface();
		byte[] appletAIDBytes = new byte[] { (byte) 0xA0, 0, 0, 0, 1, 1 };
		AID appletAID = new AID(appletAIDBytes, (short) 0,
				(byte) appletAIDBytes.length);
		simulator.installApplet(appletAID, CardEdge.class);
		simulator.selectApplet(appletAID);
		this.context = appContext;
	}

	// Eine Zugriffsmethode auf Klassenebene, welches dir '''einmal''' ein
	// konkretes
	// Objekt erzeugt und dieses zurückliefert.
	public static MSCHostApduService getInstance(Context appContext) {
		if (MSCHostApduService.instance == null) {
			MSCHostApduService.instance = new MSCHostApduService(appContext);
		}
		return MSCHostApduService.instance;
	}
	
	public static MSCHostApduService getInstance() {
		return MSCHostApduService.instance;
	}

	// Changed code from http://www.openscdp.org/scripts/musclecard/init.html
	public void setup_mscapplet(){
		byte[] getStatusApdu = {(byte) 0xB0, (byte) 0x3C, (byte) 0x00, (byte) 0x00, (byte) 0x05};
		byte[] response = sendApduToMscApplet(getStatusApdu);

		if( response[response.length - 2] == (byte) 0x9c && (byte) response[response.length - 1] == (byte) 0x05){
			// Initialize applet
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

			byte cla = (byte) 0xB0;
			byte ins = (byte) 0x2A;
			byte p1 = (byte) 0x00;
			byte p2 = (byte) 0x00;
			byte[] defaultPwd = "Muscle00".getBytes();
			/*We will set PIN0 random, because it is needed for setup,
			* but we will not need it any longer and we do not want
			* anyone to know about it. */
			byte[] PIN0 = new byte[8];
			byte[] UPIN0 = new byte[8];
			new SecureRandom().nextBytes(PIN0);
			new SecureRandom().nextBytes(UPIN0);
			//Get PIN from disk, if it exists.
			byte PIN0Tries = 0x03;
			byte UPIN0Tries = 0x03;
			byte[] PIN1;
			byte[] UPIN1;
			byte PIN1Tries = 0x03;
			byte UPIN1Tries = 0x03;
			File directory = createDirectory("pins");
			File file = new File(directory, "pin1");
			if (!file.exists()) {
				PIN1 = defaultPin.getBytes();
				UPIN1 = defaultPin.getBytes();
			} else {
				byte[] data = readByteArrayFromDisk("pins", "pin1");
				PIN1 = Arrays.copyOfRange(data, 8, 8 + data[7]);
				UPIN1 = Arrays.copyOfRange(data, 8 + data[7] + 1, 
						8 + data[7] + 1 + data[8 + data[7]]);
				PIN1Tries = data[5];
				UPIN1Tries = PIN1Tries;
			}

			try{
				//Write the stuff before the data
				outputStream.write(cla);
				outputStream.write(ins);
				outputStream.write(p1);
				outputStream.write(p2);

				// The applet verifies the default PIN0 value,...
				outputStream.write(defaultPwd.length);
				outputStream.write(defaultPwd);

				// ...then sets PIN 0,...
				outputStream.write(PIN0Tries);   // PIN0 Tries
				outputStream.write(UPIN0Tries);   // UPIN0 Tries
				outputStream.write(PIN0.length);
				outputStream.write(PIN0);
				outputStream.write(UPIN0.length);
				outputStream.write(UPIN0);

				// ...PIN1,...        
				outputStream.write(PIN1Tries);   // PIN1 Tries
				outputStream.write(UPIN1Tries);   // UPIN1 Tries
				outputStream.write(PIN1.length);
				outputStream.write(PIN1);
				outputStream.write(UPIN1.length);
				outputStream.write(UPIN1);

				// ... , ...
				byte[] zero = {(byte) 0, (byte) 0};
				outputStream.write(zero);

				// ... memory size for keys and objects and ...
				byte[] memSize = {(byte) 0x40, (byte) 0};
				outputStream.write(memSize);

				// ... access control settings for creating objects, keys and PINs
				byte[] acl = {(byte) 2, (byte) 2, (byte) 2};
				outputStream.write(acl);
			}
			catch(IOException e){
				System.err.printf("IOException in function setup_mscapplet%n");
			}
			finally{		
			}

			byte data[] = outputStream.toByteArray();
			sendApduToMscApplet(data);

			// Try MSCGetStatus
			response = sendApduToMscApplet(getStatusApdu);

			//Check SW1
			if (response[response.length - 2] == (byte) 0x61) {
				byte[] remApdu = {(byte) 0x00, (byte) 0xC0, (byte) 0x00, (byte) 0x00, response[response.length - 2]};
				sendApduToMscApplet(remApdu);
			}
			
			//Create root object
			String pin1 = new String(getPinValue((byte) 1));
			verifyPin((byte) 1, pin1, false);
			createRootObject();

			//Read Objects, Pins and Keys
			readObjectsPersistent();
			readKeysPersistent();
			
			//Attention: PIN1 can get logged out while doing readPinsPersistent.
			readPinsPersistent();	
			if(getLoginTriesLeft((byte) 1) <= 1){
				lastLoginTry = true;
				((LastLoginTryWithGUI) context).
					lastLoginTryWithGUI(getLoginTriesLeft((byte) 1),
							getUnblockTriesLeft((byte) 1));
			}

			//Logout, so PIN1 is not logged in after the app starts and PIN is still needed decrypt/sign
			//Only when PKCS15 structure has already been created.
			if(listObjects().contains("50154401"))
				logoutAll();
		}
	}

	private void initializePace(){
		byte[] pin1 = getPinValue((byte) 1);
		if(pin1 != null){
			this.pace = new Pace(new String(pin1));
		} else {
			this.pace = new Pace(defaultPin);
		}
	}

	//Needed, because OpenSC will not create it, but needs it.
	private void createRootObject(){
		byte[] createRootObjectApdu = {(byte) 0xB0, (byte) 0x5A, (byte) 0x00, (byte) 0x00,
				(byte) 0x3F, (byte) 0x00, (byte) 0x00, (byte) 0x00, 0, 0, 0,
				1, 0, 0, 0, 0, 0, 0};
		sendApduToMscApplet(createRootObjectApdu);
	}
	
	private byte[] getPinValue(byte number){
		byte[] readData = readByteArrayFromDisk("pins", "pin" + number);
		if(readData != null){
			return Arrays.copyOfRange(readData, 8, 8 + readData[7]);
		} else {
			return null;
		}
	}
	
	private byte[] getUnblockPinValue(byte number){
		byte[] readData = readByteArrayFromDisk("pins", "pin" + number);
		if(readData != null){
			return Arrays.copyOfRange(readData, 8 + readData[7] + 1,
					8 + readData[7] + 1 + readData[8 + readData[7]]);
		} else {
			return null;
		}
	}
	
	public boolean existsPin(byte number){
		if(getPinValue(number) != null)
			return true;
		else
			return false;
	}
	
	public void createPinPersistent(byte number, byte maxAttempts, String value, 
			String unblockValue){
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] createPinApduHeader = {(byte) 0xB0, (byte) 0x40, number, maxAttempts};
		byte[] valueAsBytes = value.getBytes();
		byte[] unblockValueAsBytes = unblockValue.getBytes();
		try{
			out.write(createPinApduHeader);
			out.write(1 + valueAsBytes.length + 1 + unblockValueAsBytes.length);
			out.write((byte) valueAsBytes.length);
			out.write(valueAsBytes);
			out.write((byte) unblockValueAsBytes.length);
			out.write(unblockValueAsBytes);
		}catch(IOException e){
			e.printStackTrace();
		}
		writePinPersistentOnCreate(out.toByteArray());
	}

//----------Host-based Card Emulation----------
//Not really HCE in here, HCE stuff is done in processCommandApduWrapper.	

	//Data coming from reader, already has LC byte set
	public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
		boolean encrypted = false;
		byte[] responseApdu = null;
		//Do not allow unblock PIN from reader, only from GUI
		if(apdu[1] == (byte) 0x46 || lastLoginTry){
			byte[] notAllowed = {(byte)0x69, (byte)0x00};
			responseApdu = notAllowed;
		}
		// For encrypted apdus
		if (apdu[0] == (byte) 0xBC) {
			System.out.println("Trying to decrypt encrypted apdu (" + apdu.length +" bytes):");
			System.out.println(printByteArray(apdu));
			apdu = pace.decryptCommandApdu(apdu);
			encrypted = true;
		}
		// PACE stuff
		if (apdu[1] == (byte) 0x86) {
			byte[] getNonceApdu = {(byte) 0x10, (byte) 0x86, (byte) 0x00,
					(byte) 0x00, (byte) 0x02, (byte) 0x7C, (byte) 0x00,
					(byte) 0x00};
			if(Arrays.equals(apdu, getNonceApdu)){
				initializePace();
				paceStep = 0;
				//Increment counter
				changeUnsuccessfulTriesPersistent((byte) 1, false, true);
			}
			responseApdu = pace.performPace(Arrays.copyOfRange(apdu, 5, apdu.length));
			paceStep++;
			if(paceStep == 4){
				if(!pace.existsSecureMessagingObject()){
					/* Also increase the counter in MSCApplet. We already
					 * increased it in persistent memory. */
					increaseUnsuccessfulTriesInMSCApplet((byte) 1, false);
				}
				else{
					//Set counter to 0.
					changeUnsuccessfulTriesPersistent((byte) 1, false, false);
				}
			}			
		}
		if (apdu[1] == (byte) 0x22) {
			byte[] success = {(byte) 0x90, (byte) 0x00};
			responseApdu = success;
		}
		
		//Commands with login. Increment counter now, if login was successful,
		//the counter will be set to 0 at the end of this method. 
		if((apdu[1] == (byte) 0x42 || apdu[1] == (byte) 0x44)){
			changeUnsuccessfulTriesPersistent(apdu[2], false, true);
		}
		
		//When decrypting/signing, OpenSC sends LE. MSC does not expect this.
		//OpenSC only seems to do it when data in apdu
		if(apdu[1] == (byte) 0x36 && apdu.length > 10){
			apdu = Arrays.copyOfRange(apdu, 0, apdu.length - 1);
		}
		
		if(apdu[1] == (byte) 0x36 && apdu[3] == 0x03){
			if(askForOk() == false){
				byte[] notAuthorized = {(byte) 0x9C, (byte) 0x06};
				responseApdu = notAuthorized;
			}
		}
		
		if(apdu.length >= 8){
			//Decrypting
			if(apdu[1] == (byte) 0x36 && apdu[3] == (byte) 0x01 
					&& apdu[6] == (byte) 0x04){
				modus = "Decrypt";
			}
			
			//Signing
			if(apdu[1] == (byte) 0x36 && apdu[3] == (byte) 0x01 
					&& apdu[6] == (byte) 0x01){
				modus = "Sign";
			}
		}
		
		/*When generating keypair, set read acl of private key to 0002 and never let
		any apdu get through which tries to read the private key */
		if(apdu[1] == (byte) 0x30){
			apdu[8] = (byte) 0x00;
			apdu[9] = (byte) 0x02;
		}
		
		if(apdu[1] == (byte) 0x34 && isPrivateKey(apdu[2])){
			byte[] notAuthorized = {(byte) 0x9C, (byte) 0x06};
			responseApdu = notAuthorized;
		}
		
		/* When commanding getChallenge, openSC gives also expected length,
		 * Muscle Applet does not expect this. */
		if(apdu[1] == (byte) 0x62){
			apdu = Arrays.copyOfRange(apdu, 0, apdu.length - 1);
		}

		// Reading objects not correct in OpenSC: Sends LE two times
		if (apdu[1] == (byte) 0x56 && apdu.length > 14) {
			apdu = Arrays.copyOfRange(apdu, 0, apdu.length - 1);
		}

		if(responseApdu == null){
			responseApdu = sendApduToMscApplet(apdu, true);
			if(responseApdu != null){			
				//Create Object persistent
				if(apdu[1] == (byte) 0x5A && getSwAsInt(responseApdu) == 0x9000){
					createObjectPersistent(apdu);
				}
				
				//Write object persistent
				if(apdu[1] == (byte) 0x54 && getSwAsInt(responseApdu) == 0x9000){
					writeObjectPersistent(apdu);
				}
				
				//Write key persistent
				if(apdu[1] == (byte) 0x30 && getSwAsInt(responseApdu) == 0x9000){
					writeKeysPersistent();
				}
				
				//Write PIN persistent on create
				if(apdu[1] == (byte) 0x40 && getSwAsInt(responseApdu) == 0x9000){
					writePinPersistentOnCreate(apdu);
				}
				
				//Write PIN persistent on change
				if(apdu[1] == (byte) 0x44 && getSwAsInt(responseApdu) == 0x9000){
					writePinPersistentOnChange(apdu);
				}
				
				//Change unsuccessful tries persistent
				//Successful login, set counter to 0
				if((apdu[1] == (byte) 0x42 || apdu[1] == (byte) 0x44)
						&& getSwAsInt(responseApdu) == 0x9000){
					changeUnsuccessfulTriesPersistent(apdu[2], false, false);
				}
			}
		}
		
		if(getLoginTriesLeft((byte) 1) <= 1 && paceStep == 4){
			lastLoginTry = true;
			((LastLoginTryWithGUI) context).
				lastLoginTryWithGUI(getLoginTriesLeft((byte) 1),
						getUnblockTriesLeft((byte) 1));
		}
		
		if(encrypted){
			responseApdu = pace.encryptResponseApdu(responseApdu);
			System.out.println("Encrypted outgoing apdu (" + responseApdu.length +" bytes):");
			System.out.println(printByteArray(responseApdu));
		}
			
		return responseApdu;
	}
	
//----------Miscanellous helping functions----------

	private byte[] sendApduToMscApplet(byte[] apdu) {
		return sendApduToMscApplet(apdu, false);
	}
	
	//APDUs coming from reader already have LC-Byte set, this must be ignored
	private byte[] sendApduToMscApplet(byte[] apdu, boolean fromReader) {

		System.out.println("Incoming (maybe already decrypted) apdu (" + apdu.length + " bytes):");
		System.out.println(printByteArray(apdu));
		
		if (apdu == null || apdu.length < 4) {
			System.err.printf("Method sendApduToMscApplet has been given too short apdu (apdu without header)%n");
			return null;
		}
		else{
			CommandAPDU cmdApdu;
			switch(apdu.length){
			case 4: cmdApdu = new CommandAPDU(apdu[0], apdu[1], apdu[2], 
					apdu[3]); break;
			case 5: cmdApdu = new CommandAPDU(apdu[0], apdu[1], apdu[2], 
					apdu[3], apdu[4]); break;
			//Length greater than 5: Could be with le or without, we can not know
			default:
					//Without le:
					if(fromReader){
						cmdApdu = new CommandAPDU(apdu[0], apdu[1], apdu[2], 
										apdu[3], Arrays.copyOfRange(apdu, 5, apdu.length));
					} else {
						cmdApdu = new CommandAPDU(apdu[0], apdu[1], apdu[2], 
								apdu[3], Arrays.copyOfRange(apdu, 4, apdu.length));
					}
			}
			
			//Send data to MUSCLE-Applet and convert the ResponseAPDU to a Byte-Array
			ResponseAPDU response = simulator.transmitCommand(cmdApdu);	
			byte[] response_sw1sw2 = {(byte) response.getSW1(),
					(byte) response.getSW2()};
			byte[] response_data = response.getData();
			byte[] responseApdu = new byte[response_data.length 
			                               + response_sw1sw2.length];
			System.arraycopy(response_data, 0, responseApdu, 0, 
					response_data.length);	
			System.arraycopy(response_sw1sw2, 0, responseApdu, 
					response_data.length, response_sw1sw2.length);
			
			System.out.println("Outgoing (maybe becoming encrypted) apdu (" + responseApdu.length + " bytes):");
			System.out.println(printByteArray(responseApdu));
			
			return responseApdu;
		}
	}
	
	private boolean isPrivateKey(byte keyNumber){
		byte[] listKeysApdu = {(byte) 0xB0, (byte) 0x3A, (byte) 0, 
				(byte) 0, (byte) 0x0B};
		byte[] response = sendApduToMscApplet(listKeysApdu);
		
		listKeysApdu[2] = (byte) 1;
		while(response.length > 2 && response[0] != keyNumber){
			response = sendApduToMscApplet(listKeysApdu);
		}
		if(response[1] > 1){
			return true;
		} else {
			return false;
		}
	}
	
	private void logoutAll(){
		byte[] logOutAllApdu = {(byte) 0xB0, (byte) 0x60, (byte) 0x00,
				(byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x00};
		sendApduToMscApplet(logOutAllApdu);
	}
	
	private File createDirectory(String name){
		File directory = new File(this.context.getFilesDir(),
				name);
		if (!directory.exists()){
			directory.mkdir();
			directory.setReadable(true, true);
			directory.setWritable(true, true);
			directory.setExecutable(true, true);
		}
		return directory;
	}
	
	private void increaseUnsuccessfulTriesInMSCApplet(byte number, boolean unblockPin){
		byte[] wrongPassword;
		if(unblockPin)
			wrongPassword = getUnblockPinValue(number);
		else
			wrongPassword = getPinValue(number);
		wrongPassword[0] = (byte) (wrongPassword[0] ^ (byte) 0xFF);
		if(unblockPin)
			unblockPin(number, new String(wrongPassword), false);
		else
			verifyPin(number, new String(wrongPassword), false);
	}
	
	private int getLoginTriesLeft(byte pinNumber){
		byte[] readData = readByteArrayFromDisk("pins", "pin" + pinNumber);
		return readData[5] - readData[0];
	}
	
	private int getUnblockTriesLeft(byte pinNumber){
		byte[] readData = readByteArrayFromDisk("pins", "pin" + pinNumber);
		return readData[5] - readData[1];
	}
	
//----------Converting functions----------
	
	//Converts a hex string to a byte Array
	private static byte[] string2byteArray(String str){
		if(str.length() % 2 != 0){
			System.out.println("string2byteArray got string with wrong length!");
			return null;
		}
		byte[] returnValue = new byte[str.length()/2];
		for(int i = 0; i < str.length(); i++){
			byte value;
			if(str.charAt(i) < 58){
				value = (byte) (str.charAt(i) - 48);
			} else {
				value = (byte) (str.charAt(i) - 55);
			}
			returnValue[i/2] += (byte) (value << (4 * ((i + 1) % 2)));
		}
		return returnValue;
	}

	//Code from http://stackoverflow.com/questions/7619058/convert-a-byte-array-to-integer-in-java-and-vise-versa
	private static int byteArray2int(byte[] byteArray){
		ByteBuffer wrapped = ByteBuffer.wrap(byteArray);
		return wrapped.getInt();
	}

	//Code from http://stackoverflow.com/questions/7619058/convert-a-byte-array-to-integer-in-java-and-vise-versa
	private static byte[] int2byteArray(int givenInt){
		ByteBuffer dbuf = ByteBuffer.allocate(4);
		dbuf.putInt(givenInt);
		return dbuf.array();
	}

	private static int getSwAsInt(byte[] responseApdu){
		byte[] byteArray = new byte[4];
		byteArray[0] = 0;
		byteArray[1] = 0;
		System.arraycopy(Arrays.copyOfRange(responseApdu, responseApdu.length - 2, responseApdu.length),
				0, byteArray, 2, 2);
		return byteArray2int(byteArray);
	}

	private static int byte2int(byte b){
		int returnvalue;
		if(b >= 0)
			returnvalue = b;
		else
			returnvalue = 256 + b;
		return returnvalue;
	}
	
	//Constructs ACL, with every user set who can be found in byteArray 
	private static byte[] byteArray2Acl(byte[] byteArray){		
		byte[] acl = new byte[2];
		for(int i = 0; i < byteArray.length; i++){
			//Exclude double nominations
			boolean doubleEntry = false;
			for(int j = 0; j < i; j++){
				if(byteArray[j] == byteArray[i])
					doubleEntry = true;
			}
			if(!doubleEntry)
				acl[1 - byteArray[i] / 8] += (1 << (byteArray[i] - ((byteArray[i] / 8) * 8)));
		}

		return acl;
	}

	//Constructs byte array, with every user id in it who can be found in ACL 
	private static byte[] acl2ByteArray(byte[] acl) {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		byte[] byte2Write = new byte[1];
		for (int i = 0; i < 16; i++) {
			if((acl[1 - (i / 8)] >> (i - ((i / 8) * 8))) % 2 == 1){
				byte2Write[0] = (byte) i;
				try{
					outputStream.write(byte2Write);
				}catch(IOException e){
					e.printStackTrace();
				}
			}
		}

		return outputStream.toByteArray();
	}

	//----------Creating human-readable output to show status of MUSCLE-applet----------

	//Small helping function for debugging
	//Code copied from http://stackoverflow.com/questions/9655181/convert-from-byte-array-to-hex-string-in-java
	final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
	private static String printByteArray(byte[] byteArray){
		char[] hexChars = new char[byteArray.length * 2];
		for (int i = 0; i < byteArray.length; i++) {
			int v = byteArray[i] & 0xFF;
			hexChars[i * 2] = hexArray[v >>> 4];
			hexChars[i * 2 + 1] = hexArray[v & 0x0F];
		}
		return new String(hexChars);
	}
	
	//Returns a string which describes human-readable, which identities are required in the acl.
	private static String printAcl(byte[] acl){
		String result = "";
		byte[] byteArrayWithIdentities = acl2ByteArray(acl);
		for(int i = 0; i < byteArrayWithIdentities.length; i++){
			result = result.concat("\t\t\t PIN " + byteArrayWithIdentities[i] 
					+ " is needed.\n");
		}
		if(byteArrayWithIdentities.length == 0){
			result = result.concat("\t\t\t No PIN is needed.\n");
		}
		return result;
	}

	private String listObjects(){
		byte[] listObjectsApdu = {(byte) 0xB0, (byte) 0x58, (byte) 0, 
				(byte) 0, (byte) 0x0E};
		byte[] response = sendApduToMscApplet(listObjectsApdu);
		
		listObjectsApdu[2] = (byte) 1;
		String returnValue = new String();
		while(response.length > 2){
			returnValue = returnValue.concat("\t Object ID: " + 
							printByteArray(Arrays.copyOfRange(response, 0, 4)) + "\n");
			returnValue = returnValue.concat("\t Object Size: " + 
							byteArray2int(Arrays.copyOfRange(response, 4, 8)) + " Bytes.\n");
			returnValue = returnValue.concat("\t Object ACLS:\n");
			returnValue = returnValue.concat("\t\t Read: \n" +
							printAcl(Arrays.copyOfRange(response, 8, 10)));
			returnValue = returnValue.concat("\t\t Write: \n" +
							printAcl(Arrays.copyOfRange(response, 10, 12)));
			returnValue = returnValue.concat("\t\t Delete: \n" +
							printAcl(Arrays.copyOfRange(response, 12, 14)));
			returnValue = returnValue.concat("----------------------------------------\n");
			response = sendApduToMscApplet(listObjectsApdu);
		}
		return returnValue;
	}
	
	private String getStatus(){
		byte[] getStatusApdu = {(byte) 0xB0, (byte) 0x3C, (byte) 0x00, (byte) 0x00, (byte) 0x05};
		byte[] response = sendApduToMscApplet(getStatusApdu);
		String returnValue = new String();
		returnValue = returnValue.concat("Card Edge Version: " + response[0] + "." + response[1] + '\n');
		returnValue = returnValue.concat("MUSCLE Applet Version: " + response[2] + "." + response[3] + '\n');
		returnValue = returnValue.concat("Total Object Memory: " + byteArray2int(Arrays.copyOfRange(response, 4, 8)) + " Bytes.\n");
		returnValue = returnValue.concat("Free Object Memory: " + byteArray2int(Arrays.copyOfRange(response, 8, 12)) + " Bytes.\n");
		returnValue = returnValue.concat("Number of used PINs: " + response[12] + '\n');
		returnValue = returnValue.concat("Number of used keys: " + response[13] + '\n');
		returnValue = returnValue.concat("Currently logged in identities:" + '\n');
		boolean loggedIn = false;
		for(int i = 0; i < 2; i++){
	    	if(((response[14] >> i) % 2) == 1){
	    		loggedIn = true;
	    		returnValue = returnValue.concat("\t Reserved Identity " + i + " is currently logged in." + '\n');
	    	}
	    }
		for(int i = 2; i < 8; i++){
	    	if(((response[14] >> i) % 2) == 1){
	    		loggedIn = true;
	    		returnValue = returnValue.concat("\t Strong Identity " + i + " is currently logged in." + '\n');
	    	}
	    }
		for(int i = 0; i < 8; i++){
	    	if(((response[15] >> i) % 2) == 1){
	    		loggedIn = true;
	    		returnValue = returnValue.concat("\t PIN " + i + " is currently logged in." + '\n');
	    	}
	    }
		if(!loggedIn){
			returnValue = returnValue.concat("\t None.\n");
		}
		return returnValue;
	}
	
	private static String keyType2String(byte keyType){
		switch(keyType){
			case 1: return "Public RSA Key";
			case 2: return "Private RSA Key";
			case 3: return "Private RSA CRT Key";
			default: return "Unknown";
		}
	}
	
	private String listKeys(){
		byte[] listKeysApdu = {(byte) 0xB0, (byte) 0x3A, (byte) 0, 
				(byte) 0, (byte) 0x0B};
		byte[] response = sendApduToMscApplet(listKeysApdu);
		
		listKeysApdu[2] = (byte) 1;
		String returnValue = new String();
		while(response.length > 2){
			returnValue = returnValue.concat("\t Key Number: " + response[0] + "\n");
			returnValue = returnValue.concat("\t Key Type: " + keyType2String(response[1]) + "\n");
			returnValue = returnValue.concat("\t Key Size: " + (response[3] * 256 + response[4]) + " Bit.\n");
			returnValue = returnValue.concat("\t Key ACLS:\n");
			returnValue = returnValue.concat("\t\t Read: \n" + printAcl(Arrays.copyOfRange(response, 5, 7)));
			returnValue = returnValue.concat("\t\t Write: \n" + printAcl(Arrays.copyOfRange(response, 7, 9)));
			returnValue = returnValue.concat("\t\t Use: \n" + printAcl(Arrays.copyOfRange(response, 9, 11)));
			returnValue = returnValue.concat("----------------------------------------\n");
			response = sendApduToMscApplet(listKeysApdu);
		}
		return returnValue;
	}
	
	public String getKeyCardStatus(){
		String returnValue = "";
		returnValue = returnValue.concat("======= Status of MUSCLE Applet =======\n");
		returnValue = returnValue.concat(getStatus());
		returnValue = returnValue.concat("======= Keys in MUSCLE Applet =======\n");
		returnValue = returnValue.concat(listKeys());
		returnValue = returnValue.concat("======= Objects in MUSCLE Applet =======\n");
		returnValue = returnValue.concat(listObjects());
		return returnValue;
	}
	
	private boolean askForOk(){
		//KeyEvent event = new KeyEvent(modus.equals("Decrypt") ? 0 : 1, 0);
		//boolean result = ((Activity) context).dispatchKeyEvent(event);
		String data = modus + "? Data is ";
		byte[] importObjectId = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE};
		byte[] dataToComputeCryptWith = readObject(importObjectId);
		data = data.concat(printByteArray(Arrays.copyOfRange(dataToComputeCryptWith, 2, 
					dataToComputeCryptWith.length)));
		boolean result = ((AskForOk) context).askForOk(data);
		return result;
	}
	
//----------Writing data to persistent memory----------
	
	private void writeByteArray2Disk(String directoryName, String fileName, byte[] data){
		File directory = createDirectory(directoryName);

		try {
			File file = new File(directory, fileName);
			FileOutputStream fos = new FileOutputStream(file);
			DataOutputStream dos = new DataOutputStream(fos);
			dos.write(data);
			dos.close();
		}catch(IOException e){
			e.printStackTrace();
		}
	}
	
	private byte[] getObjectSize(byte[] id){
		if(id.length != 4){
			System.out.println("Error in getObjectSize! ID not correct!");
			return null;
		}
		
		byte[] listObjectsApdu = {(byte) 0xB0, (byte) 0x58, (byte) 0, 
				(byte) 0, (byte) 0x0E};
		byte[] response = sendApduToMscApplet(listObjectsApdu);
		
		listObjectsApdu[2] = (byte) 1;
		while(response.length > 2 && !Arrays.equals(Arrays.copyOfRange(response, 0, 4), id)){
			response = sendApduToMscApplet(listObjectsApdu);
		}
		
		return Arrays.copyOfRange(response, 4, 8);
	}
	
	private byte[] readObject(byte[] id){
		ByteArrayOutputStream responseStream = new ByteArrayOutputStream();
		byte[] readObjectApduHeader = {(byte) 0xB0, (byte) 0x56, (byte) 0x00, (byte) 0x00};
		byte[] objectSizeAsBytes = getObjectSize(id);
		//Attention: if keySize > MAX_INT/2, it will be negative, which will lead to problems in the code
		int objectSize = byteArray2int(objectSizeAsBytes);
		
		if(objectSize < 0){
			System.out.println("Error when exporting object: object is too big.");
			return null;
		}
		
		byte[] offsetAsBytes = {(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00};
		int offset = byteArray2int(offsetAsBytes);
		byte sizeToGet;
		
		while(objectSize > 0){
			ByteArrayOutputStream apduStream = new ByteArrayOutputStream();
			
			//We need to get more than one apdu
			if(objectSize > 0xFF)
				sizeToGet = (byte) 0xFF;
			//We only need to get one (the last) apdu
			else
				sizeToGet = (byte) objectSize;
			objectSize -= byte2int(sizeToGet);
			offsetAsBytes = int2byteArray(offset);
			try{
				apduStream.write(readObjectApduHeader);
				apduStream.write(id);
				apduStream.write(offsetAsBytes);
				apduStream.write(sizeToGet);
			}
			catch(IOException e){
				System.out.println("IO-Error in readOutputObject.");
			}
			
			byte[] response = sendApduToMscApplet(apduStream.toByteArray());
			
			try{
				responseStream.write(Arrays.copyOfRange(response, 0, response.length - 2));
			}
			catch(IOException e){
				System.out.println("IO-Error in readOutputObject.");
			}
			
			offset += 0xFF;
			
			/*
			try{
				System.in.read();
			} catch(Exception e){
				
			}*/
		}		
		
		return responseStream.toByteArray();
	}
	
	private void deleteExportObject(){
		byte[] deleteExportObjectApdu = {(byte) 0xB0, (byte) 0x52, (byte) 0x00, (byte) 0x01,
				(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
		sendApduToMscApplet(deleteExportObjectApdu);
	}
	
	private void exportKey(byte number){
		byte[] exportKeyApdu = {(byte) 0xB0, (byte) 0x34, number, 
	    		(byte) 0, (byte) 0x00};
	    sendApduToMscApplet(exportKeyApdu);
	}
	
	private void writeKeysPersistent() {
		//Get keys one after another:
		boolean[] existingKeys = new boolean[16];
		byte[] listKeysApdu = { (byte) 0xB0, (byte) 0x3A, (byte) 0, (byte) 0,
				(byte) 0x0B };
		byte[] response = sendApduToMscApplet(listKeysApdu);

		listKeysApdu[2] = (byte) 1;

		do {
			existingKeys[response[0]] = true;
			exportKey(response[0]);
			try {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				// Write ACLs first:
				out.write(Arrays.copyOfRange(response, 5, 7));
				out.write(Arrays.copyOfRange(response, 7, 9));
				out.write(Arrays.copyOfRange(response, 9, 11));
				// Then write modulus and exponent
				byte[] exportObjectId = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
				out.write(readObject(exportObjectId));
				deleteExportObject();
				writeByteArray2Disk("keys", "key" + response[0],
						out.toByteArray());
			} catch (IOException e) {
				e.printStackTrace();
			}
			response = sendApduToMscApplet(listKeysApdu);
		} while (response.length > 2);

		File keyDirectory = new File(this.context.getFilesDir(), "keys");
		if (keyDirectory.exists()) {
			// Delete files of keys which could not be found
			for (int i = 0; i < 16; i++) {
				if (!existingKeys[i]) {
					File keyFile = new File(keyDirectory, "key" + i);
					if (keyFile.exists()) {
						keyFile.delete();
					}
				}
			}
		}
	}
	
	private void writeObjectPersistent(byte[] writeObjectApdu){
		byte[] objectID = Arrays.copyOfRange(writeObjectApdu, 5, 9);
		byte[] offset = Arrays.copyOfRange(writeObjectApdu, 9, 13);
		byte[] data = Arrays.copyOfRange(writeObjectApdu, 14, writeObjectApdu.length);
		byte[] readData = readByteArrayFromDisk("objects", printByteArray(objectID));
		
		if(readData != null){
			//There is already data in the file
			int length = Math.max(readData.length, byteArray2int(offset) + data.length);
			byte[] newData = new byte[length];
			System.arraycopy(readData, 0, newData, 0, readData.length);
			System.arraycopy(data, 0, newData, byteArray2int(offset), data.length);
			writeByteArray2Disk("objects", printByteArray(objectID), newData);
		} else {
			//File has been empty
			byte[] newData = new byte[byteArray2int(offset) + data.length];
			System.arraycopy(data, 0, newData, byteArray2int(offset), data.length);
			writeByteArray2Disk("objects", printByteArray(objectID), newData);
		}
	}
	
	private void createObjectPersistent(byte[] createObjectApdu){
		byte[] writeableCreateObjectApdu = new byte[createObjectApdu.length - 1];
		System.arraycopy(createObjectApdu, 0, writeableCreateObjectApdu, 0, 4);
		System.arraycopy(createObjectApdu, 5, writeableCreateObjectApdu, 4, createObjectApdu.length - 5);
		writeByteArray2Disk("objects", printByteArray(Arrays.copyOfRange(createObjectApdu, 5, 9)) + "_c", writeableCreateObjectApdu);
	}
	
	private void writePinPersistentOnCreate(byte[] createPinApdu){		
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		//Write unsuccessful tries and unsuccessful unblock tries first
		out.write((byte) 0);
		out.write((byte) 0);
		
		try{
			out.write(createPinApdu);
		}catch(IOException e){
			e.printStackTrace();
		}
		writeByteArray2Disk("pins", "pin" + createPinApdu[2], out.toByteArray());
	}
	
	private void writePinPersistentOnChange(byte[] changePinApdu) {
		byte[] readData = readByteArrayFromDisk("pins", "pin"
				+ changePinApdu[2]);
		byte[] createPinApdu = Arrays.copyOfRange(readData, 2, readData.length);

		try {
			ByteArrayOutputStream outputStreamChanged = new ByteArrayOutputStream();
			//Write unsuccessfultries and unsuccessfulunblocktries first
			outputStreamChanged.write(readData[0]);
			outputStreamChanged.write(readData[1]);
			outputStreamChanged.write(Arrays.copyOfRange(createPinApdu, 0, 4));
			outputStreamChanged.write(Arrays.copyOfRange(changePinApdu,
					changePinApdu[5] + 6, changePinApdu.length));
			outputStreamChanged.write(Arrays.copyOfRange(createPinApdu,
					createPinApdu[5] + 6, createPinApdu.length));
			
			//Set LC-Byte
			byte[] byteArrayWithoutLc = outputStreamChanged.toByteArray();
			byte lc = (byte) (byteArrayWithoutLc.length - 2 - 4);
			byte[] byteArrayWithLc = new byte[2 + 4 + 1 + lc];
			System.arraycopy(byteArrayWithoutLc, 0, byteArrayWithLc, 0, 6);
			byteArrayWithLc[6] = lc;
			System.arraycopy(byteArrayWithoutLc, 6, byteArrayWithLc, 7, lc);
			
			writeByteArray2Disk("pins", "pin" + changePinApdu[2],
					byteArrayWithLc);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void changeUnsuccessfulTriesPersistent(byte number,
			boolean unblockPin, boolean increase) {
		byte[] readData = readByteArrayFromDisk("pins", "pin" + number);
		if(readData != null){
			byte[] unsuccessfulTries = new byte[1];
			byte[] unsuccessfulUnblockTries = new byte[1];
			unsuccessfulTries[0] = readData[0];
			unsuccessfulUnblockTries[0] = readData[1];
	
			// Do the requested change
			if (unblockPin) {
				if (increase) {
					unsuccessfulUnblockTries[0]++;
				} else {
					unsuccessfulUnblockTries[0] = 0;
				}
			} else {
				if (increase) {
					unsuccessfulTries[0]++;
				} else {
					unsuccessfulTries[0] = 0;
				}
			}
	
			// Write changes to disk
			try {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				out.write(unsuccessfulTries);
				out.write(unsuccessfulUnblockTries);
				out.write(Arrays.copyOfRange(readData, 2, readData.length));
				writeByteArray2Disk("pins", "pin" + number, out.toByteArray());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		if(!increase)
			lastLoginTry = false;
	}
	
//----------Reading data from persistent memory----------
	
	private byte[] readByteArrayFromDisk(String directoryName, String fileName){
		File directory = new File(this.context.getFilesDir(),
				directoryName);
		if (directory.exists()) {
			File file = new File(directory, fileName);
			if (file.exists()) {
				FileInputStream fis;
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				try {
					fis = new FileInputStream(file);
					// Read data
					int readBytes;
					do {
						byte[] buffer = new byte[100];
						readBytes = fis.read(buffer);
						if(readBytes > 0){
							outputStream.write(Arrays.copyOfRange(buffer, 0,
									readBytes));
						}
					} while (readBytes == 100);
					fis.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				return outputStream.toByteArray();
			}
		}
		return null;
	}
	
	private void readObjectsPersistent(){
		byte[] header = {(byte) 0xB0, (byte) 0x54, (byte) 0x00, (byte) 0x00};
		File objectsDirectory = createDirectory("objects");
		File[] files = objectsDirectory.listFiles();
		if(files != null){
			//First, create all files
			for(int i = 0; i < files.length; i++){
				String fileName = files[i].getName();
				if(fileName.endsWith("_c")){
					byte[] readData = readByteArrayFromDisk("objects", fileName);
					if(readData != null){
						sendApduToMscApplet(readData);
					}
				}
			}
			//All files have been created, now we can write into them
			for(int i = 0; i < files.length; i++){
				String fileName = files[i].getName();
				byte[] objectId;
				if(!fileName.endsWith("_c")){
					objectId = string2byteArray(fileName);
					byte[] readData = readByteArrayFromDisk("objects", fileName);
					if(readData != null){
						int offset = 0;
						while(offset != readData.length){
							byte[] apdu = new byte[Math.min(readData.length -offset + 13, 259)];
							System.arraycopy(header, 0, apdu, 0, header.length);
							System.arraycopy(objectId, 0, apdu, header.length, objectId.length);
							System.arraycopy(int2byteArray(offset), 0, apdu, 
									header.length + objectId.length, int2byteArray(offset).length);
							apdu[12] = (byte) (apdu.length - 13);
							System.arraycopy(readData, offset, apdu, 
									header.length + objectId.length + int2byteArray(offset).length + 1, 
									apdu.length - 13);
							sendApduToMscApplet(apdu);
							offset += (apdu.length - 13);
						}
					}
				}
			}
		}
	}
	
	private void createImportObject(byte[] size, byte[] acl_r, byte[] acl_w, byte[] acl_u){
		byte[] createImportObjectApdu = {(byte) 0xB0, (byte) 0x5A, (byte) 0x00, (byte) 0x00,
				(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE, size[0], size[1], size[2],
				size[3], acl_r[0], acl_r[1], acl_w[0], acl_w[1], acl_u[0], acl_u[1]};
		sendApduToMscApplet(createImportObjectApdu);
	}
	
	private void writeImportObject(byte[] data){
		byte sizeToSend;
		byte[] writeObjectApduHeader = {(byte) 0xB0, (byte) 0x54, (byte) 0x00, (byte) 0x00};
		byte[] importObjectId = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE};
		byte[] offsetAsBytes = {(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00};
		int offset = byteArray2int(offsetAsBytes);
		
		while(offset != data.length){
			ByteArrayOutputStream apduStream = new ByteArrayOutputStream();
			sizeToSend = (byte) Math.min(data.length - offset, 246);
			offsetAsBytes = int2byteArray(offset);
			try{
				apduStream.write(writeObjectApduHeader);
				apduStream.write(importObjectId);
				apduStream.write(offsetAsBytes);
				apduStream.write(sizeToSend);
				apduStream.write(Arrays.copyOfRange(data, offset, offset + byte2int(sizeToSend)));
			}
			catch(IOException e){
				System.out.println("IO-Error in writeKeyImportObject.");
			}
			sendApduToMscApplet(apduStream.toByteArray());
			offset += byte2int(sizeToSend);
		}
	}
	
	private void importKey(byte number, byte[] acl_r, byte[] acl_w, byte[] acl_u){
		byte[] importKeyApduHeader = {(byte) 0xB0, (byte) 0x32, number, (byte) 0x00};
		byte[] importKeyApdu = new byte[importKeyApduHeader.length + acl_r.length
		                                + acl_w.length + acl_u.length];
		System.arraycopy(importKeyApduHeader, 0, importKeyApdu, 
				0, importKeyApduHeader.length);
		System.arraycopy(acl_r, 0, importKeyApdu, 
				importKeyApduHeader.length, acl_r.length);
		System.arraycopy(acl_w, 0, importKeyApdu, 
				importKeyApduHeader.length + acl_r.length, acl_w.length);
		System.arraycopy(acl_u, 0, importKeyApdu, 
				importKeyApduHeader.length + acl_r.length + acl_w.length, acl_u.length);
		
		sendApduToMscApplet(importKeyApdu);
	}
	
	private void deleteImportObject(){
		byte[] deleteImportObjectApdu = {(byte) 0xB0, (byte) 0x52, (byte) 0x00, (byte) 0x01,
				(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE};
		sendApduToMscApplet(deleteImportObjectApdu);
	}
	
	private void importKeyFromData(byte number, byte[] data, byte[] acl_r, byte[] acl_w, byte[] acl_u){
		acl_r = byteArray2Acl(acl_r);
		acl_w = byteArray2Acl(acl_w);
		acl_u = byteArray2Acl(acl_u);
		deleteImportObject();
	  	createImportObject(int2byteArray(data.length), acl_r, acl_w, acl_u);
	  	writeImportObject(data);
	  	listObjects();
	  	importKey(number, acl_r, acl_w, acl_u);
	  	deleteImportObject();
	  	listObjects();
	}
	
	private void readKeysPersistent(){
		for(int i = 0; i < 16; i++){
			byte[] readData = readByteArrayFromDisk("keys", "key" + i);
			if(readData != null){
				//Read ACLs first:
				byte[] acl_r;
				byte[] acl_w;
				byte[] acl_u;
				acl_r = acl2ByteArray(Arrays.copyOfRange(readData, 0, 2));
				acl_w = acl2ByteArray(Arrays.copyOfRange(readData, 2, 4));
				acl_u = acl2ByteArray(Arrays.copyOfRange(readData, 4, 6));
				//Then read modulus and exponent
				importKeyFromData((byte) i, Arrays.copyOfRange(readData, 6, readData.length), 
						acl_r, acl_w, acl_u);
			}
		}
	}
	
	public boolean verifyPin(byte number, String pin){
		return verifyPin(number, pin, true);
	}
	
	private boolean verifyPin(byte number, String pin, boolean withSaving){
		//Increase counter, decrease later if login successful
		if(withSaving)
			changeUnsuccessfulTriesPersistent(number, false, true);
		
	    byte[] verifyPinHeader = {(byte) 0xB0, (byte) 0x42, number, (byte) 0};
	    byte[] pinToVerify = pin.getBytes();
	    byte[] verifyPinApdu = new byte[verifyPinHeader.length + pinToVerify.length];
	    System.arraycopy(verifyPinHeader, 0, verifyPinApdu, 0, 
				verifyPinHeader.length);
		System.arraycopy(pinToVerify, 0, verifyPinApdu, 
				verifyPinHeader.length, pinToVerify.length);
		byte[] response = sendApduToMscApplet(verifyPinApdu);
		
		if(getSwAsInt(response) == 0x9000){
			//Set counter to 0, if login successful
			if(withSaving)
				changeUnsuccessfulTriesPersistent(number, false, false);
			return true;
		}
		return false;
	}
	
	public boolean unblockPin(byte number, String pin){
		return unblockPin(number, pin, true);
	}
	
	private boolean unblockPin(byte number, String password, boolean withSaving){
		//Increase counter, decrease later if login successful
		if(withSaving)
			changeUnsuccessfulTriesPersistent(number, true, true);
		
		byte[] unblockPinHeader = {(byte) 0xB0, (byte) 0x46, number, (byte) 0};
	    byte[] passwordForUnblock = password.getBytes();
	    byte[] unblockPinApdu = new byte[unblockPinHeader.length + passwordForUnblock.length];
	    System.arraycopy(unblockPinHeader, 0, unblockPinApdu, 0, 
				unblockPinHeader.length);
		System.arraycopy(passwordForUnblock, 0, unblockPinApdu, 
				unblockPinHeader.length, passwordForUnblock.length);
		byte[] response = sendApduToMscApplet(unblockPinApdu);
		
		if(getSwAsInt(response) == 0x9000){
			//Set counter to 0, if login successful
			if(withSaving){
				changeUnsuccessfulTriesPersistent(number, true, false);
				//Also set counter of normal PIN to 0.		
				changeUnsuccessfulTriesPersistent(number, false, false);
			}
			return true;
		}
		return false;
	}

	// Reads PINs from disk and inserts them into Muscle Applet
	private void readPinsPersistent() {
		for (int i = 0; i < 16; i++) {
			byte[] readData = readByteArrayFromDisk("pins", "pin" + i);
			if (readData != null) {
				byte unsuccessfulTries = readData[0];
				byte unsuccessfulUnblockTries = readData[1];
				byte[] createPinApdu = Arrays.copyOfRange(readData, 2,
						readData.length);
				// Create PIN in Muscle Applet
				sendApduToMscApplet(createPinApdu);
				// Increase number of unsuccessful tries in Muscle Applet
				for (int j = 0; j < unsuccessfulTries; j++) {
					increaseUnsuccessfulTriesInMSCApplet((byte) i, false);
				}
				/* Increase number of unsuccessful unblock tries in Muscle
				 * Applet */
				for (int j = 0; j < unsuccessfulUnblockTries; j++) {
					increaseUnsuccessfulTriesInMSCApplet((byte) i, true);
				}
			}
		}
	}
}