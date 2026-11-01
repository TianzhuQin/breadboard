package controllers;

import org.junit.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Where the console says the other computers on this computer's networks reach the server
 * (Application.lanAddresses, for Settings → Researchers' handout and the note beside participant
 * links): as the launchers' "On this network" lines, the default route's address first, and none of
 * a network that only this computer is on (its virtual machines', a VPN's).
 */
public class LanAddressesTest {

  private static Inet4Address ip(String address) throws Exception {
    return (Inet4Address) InetAddress.getByName(address);
  }

  private static LanAddresses.Nic nic(String name, String description, int index, boolean usable, String... addresses) throws Exception {
    List<Inet4Address> list = new ArrayList<Inet4Address>();
    for (String address : addresses) {
      list.add(ip(address));
    }
    return new LanAddresses.Nic(name, description, index, usable, list);
  }

  private static List<String> chosen(List<LanAddresses.Nic> nics, String outward) throws Exception {
    List<String> addresses = new ArrayList<String>();
    for (Inet4Address address : LanAddresses.choose(nics, outward == null ? null : ip(outward))) {
      addresses.add(address.getHostAddress());
    }
    return addresses;
  }

  /**
   * A Mac on Wi-Fi (en0, the default route's) running a Parallels VM (vnic0) and a UTM one
   * (bridge100), with a VPN (utun3), AirDrop (awdl0) and Internet Sharing over Wi-Fi (ap1): Java lists
   * them from the highest index down, the VMs' first, and the handout gave colleagues the first.
   */
  @Test
  public void aMacsVirtualMachinesAndVpnAreLeftOutAndItsWiFiComesFirst() throws Exception {
    List<LanAddresses.Nic> nics = Arrays.asList(
        nic("bridge100", "bridge100", 26, true, "192.168.64.1"),
        nic("vnic0", "vnic0", 25, true, "10.211.55.2"),
        nic("utun3", "utun3", 22, false, "100.64.0.2"),
        nic("awdl0", "awdl0", 16, true),
        nic("en0", "en0", 15, true, "10.254.125.201"),
        nic("ap1", "ap1", 14, true, "192.168.2.1"),
        nic("lo0", "lo0", 1, false, "127.0.0.1"));
    assertEquals(Arrays.asList("10.254.125.201"), chosen(nics, "10.254.125.201"));
    // With an Ethernet adapter too (en7), it comes after the default route's, VMware's (vmnet8) not
    List<LanAddresses.Nic> more = new ArrayList<LanAddresses.Nic>(nics);
    more.add(0, nic("vmnet8", "vmnet8", 30, true, "172.16.45.1"));
    more.add(0, nic("en7", "en7", 20, true, "192.168.1.20"));
    assertEquals(Arrays.asList("10.254.125.201", "192.168.1.20"), chosen(more, "10.254.125.201"));
    // the default route through the Ethernet adapter: it comes first
    assertEquals(Arrays.asList("192.168.1.20", "10.254.125.201"), chosen(more, "192.168.1.20"));
  }

  /** A lab's network may hand out public addresses: those count (only 127.x and 169.254.x don't). */
  @Test
  public void publicAddressesCountLinkLocalOnesDont() throws Exception {
    List<LanAddresses.Nic> nics = Arrays.asList(
        nic("en0", "en0", 4, true, "128.36.12.7"),
        nic("en5", "en5", 9, true, "169.254.10.3"));
    assertEquals(Arrays.asList("128.36.12.7"), chosen(nics, "128.36.12.7"));
  }

  /**
   * With a VPN that takes the default route (point-to-point, utun or tun), its address isn't one the
   * lab's computers reach: the others are listed, by their interfaces' order.
   */
  @Test
  public void aVpnThatTakesTheDefaultRouteIsLeftOut() throws Exception {
    List<LanAddresses.Nic> nics = Arrays.asList(
        nic("utun4", "utun4", 23, false, "10.8.0.6"),
        nic("en1", "en1", 12, true, "192.168.0.30"),
        nic("en0", "en0", 6, true, "10.0.0.12"));
    assertEquals(Arrays.asList("10.0.0.12", "192.168.0.30"), chosen(nics, "10.8.0.6"));
    // and without any default route (no internet), the same
    assertEquals(Arrays.asList("10.0.0.12", "192.168.0.30"), chosen(nics, null));
  }

  /**
   * A Linux server: Docker's, libvirt's and LXD's networks are left out, unless the default route goes
   * through such an interface, as it does through a bridge that holds the server's own address.
   */
  @Test
  public void linuxContainerNetworksAreLeftOutButNotTheDefaultRoutesBridge() throws Exception {
    List<LanAddresses.Nic> nics = Arrays.asList(
        nic("eth0", "eth0", 2, true, "10.1.2.3"),
        nic("docker0", "docker0", 3, true, "172.17.0.1"),
        nic("br-3f2a9c1d7e44", "br-3f2a9c1d7e44", 4, true, "172.18.0.1"),
        nic("virbr0", "virbr0", 5, true, "192.168.122.1"),
        nic("lxdbr0", "lxdbr0", 6, true, "10.99.1.1"),
        nic("tailscale0", "tailscale0", 7, false, "100.101.102.103"),
        nic("wg0", "wg0", 8, false, "10.66.0.2"));
    assertEquals(Arrays.asList("10.1.2.3"), chosen(nics, "10.1.2.3"));
    // a host whose network is on a bridge (br0 here; bridge0 elsewhere): it is the default route's
    List<LanAddresses.Nic> bridged = Arrays.asList(
        nic("eth0", "eth0", 2, true),
        nic("bridge0", "bridge0", 3, true, "10.1.2.4"),
        nic("virbr0", "virbr0", 5, true, "192.168.122.1"));
    assertEquals(Arrays.asList("10.1.2.4"), chosen(bridged, "10.1.2.4"));
  }

  /**
   * Windows, where Java names interfaces eth0, wlan1, ... and the adapter's description says what it
   * is: Hyper-V's (WSL's too), VirtualBox's and VMware's adapters are left out, unless the default
   * route goes through one (a server on Hyper-V, or with Hyper-V's external switch).
   */
  @Test
  public void windowsVirtualAdaptersAreLeftOutByWhatTheyAre() throws Exception {
    List<LanAddresses.Nic> nics = Arrays.asList(
        nic("wlan1", "Intel(R) Wi-Fi 6 AX201 160MHz", 7, true, "192.168.1.44"),
        nic("eth5", "Hyper-V Virtual Ethernet Adapter", 31, true, "172.25.80.1"),
        nic("eth6", "VirtualBox Host-Only Ethernet Adapter", 12, true, "192.168.56.1"),
        nic("eth7", "VMware Virtual Ethernet Adapter for VMnet8", 14, true, "192.168.234.1"),
        nic("eth8", "Realtek USB GbE Family Controller", 18, true, "10.20.0.15"));
    assertEquals(Arrays.asList("192.168.1.44", "10.20.0.15"), chosen(nics, "192.168.1.44"));
    List<LanAddresses.Nic> external = Arrays.asList(
        nic("eth3", "Hyper-V Virtual Ethernet Adapter #2", 9, true, "10.20.0.16"),
        nic("eth4", "Hyper-V Virtual Ethernet Adapter", 11, true, "172.25.80.1"));
    assertEquals(Arrays.asList("10.20.0.16"), chosen(external, "10.20.0.16"));
  }

  /** The default route's address comes first among its interface's, and each address once. */
  @Test
  public void theDefaultRoutesAddressComesFirstAndNoneTwice() throws Exception {
    List<LanAddresses.Nic> nics = Arrays.asList(
        nic("en0", "en0", 4, true, "192.168.1.9", "192.168.1.10"),
        nic("en1", "en1", 5, true, "192.168.1.10"));
    assertEquals(Arrays.asList("192.168.1.10", "192.168.1.9"), chosen(nics, "192.168.1.10"));
  }

  /** On this computer: the port of the address the console was opened at, and the default route's first. */
  @Test
  public void thisComputersAddressesCarryThePortTheDefaultRoutesFirst() {
    List<String> urls = Application.lanAddresses("localhost:19001");
    for (String url : urls) {
      assertTrue(url, url.matches("http://[0-9.]+:19001"));
      assertFalse(url, url.startsWith("http://127.") || url.startsWith("http://169.254."));
    }
    InetAddress outward = LanAddresses.outward();
    for (LanAddresses.Nic nic : LanAddresses.interfaces()) {
      if (nic.usable && outward != null && nic.addresses.contains(outward)) {
        assertEquals("the default route's address first", "http://" + outward.getHostAddress() + ":19001", urls.get(0));
      } else if (nic.hostOnly() || !nic.usable) {
        for (Inet4Address address : nic.addresses) {
          assertFalse(nic.name + "'s address is left out: " + urls, urls.contains("http://" + address.getHostAddress() + ":19001"));
        }
      }
    }
    assertEquals(":9000", Application.portOf("localhost:9000"));
    assertEquals(":9000", Application.portOf("[::1]:9000"));
    assertEquals("", Application.portOf("[::1]"));
    assertEquals("", Application.portOf("breadboard.example.org"));
  }
}
