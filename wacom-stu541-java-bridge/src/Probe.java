import com.WacomGSS.STU.Tablet;
import com.WacomGSS.STU.TlsDevice;
import com.WacomGSS.STU.Protocol.Information;

public final class Probe {
  public static void main(String[] args) throws Exception {
    TlsDevice[] devices = TlsDevice.getTlsDevices();
    System.out.println("TLS devices: " + devices.length);
    if (devices.length == 0) {
      System.exit(2);
    }

    Tablet tablet = new Tablet();
    try {
      int result = tablet.tlsConnect(devices[0]);
      System.out.println("tlsConnect: " + result);
      if (result != 0) {
        System.exit(3);
      }
      Information info = tablet.getInformation();
      System.out.println("Model: " + info.getModelName());
      System.out.println("Firmware: " + info.getFirmwareMajorVersion() + "." + info.getFirmwareMinorVersion());
      System.out.println("Connected: " + tablet.isConnected());
    } finally {
      tablet.disconnect();
    }
  }
}
