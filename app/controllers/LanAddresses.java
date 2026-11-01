package controllers;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Where the other computers on this computer's networks (a lab's) reach it, as the launchers' "On this
 * network" lines say: the IPv4 addresses of its interfaces that are up, other than loopback (127.x)
 * and link-local (169.254.x) ones, those of the interface the default route goes through first, then
 * the others by the interfaces' order (their index, as ifconfig and ip list them).
 *
 * Left out are point-to-point interfaces (VPNs: utun on a Mac) and the networks of virtual machines,
 * containers and the like that only this computer is on (Parallels' vnic0, UTM's, Apple's and
 * Internet Sharing's bridge100, VMware's vmnet8, Docker's docker0, Hyper-V's and WSL's vEthernet,
 * AirDrop's awdl0): an address there reaches nothing but this computer's own virtual machines, and
 * the console's handout (Settings → Researchers) gives the first one to colleagues. One of those that
 * the default route goes through stays, first (a Linux server whose network is on a bridge, br0; a
 * Windows server on Hyper-V's), unless it is a point-to-point one.
 */
final class LanAddresses {

  /**
   * The names of host-only and virtual interfaces: macOS's (bridge, vnic, vmnet, vmenet, feth, utun,
   * awdl, llw, anpi, ap, gif, stf, ipsec, ppp), Linux's (docker, br-, virbr, veth, vboxnet, lxc, cni,
   * flannel, cali, podman, tun, tap, wg, zt, tailscale), and those Java gives tunnels on Windows
   * (tun, ppp). The launchers leave out the same (release/breadboard.sh).
   */
  static final Pattern HOST_ONLY_NAME = Pattern.compile(
      "(bridge|vnic|vmnet|vmenet|feth|utun|awdl|llw|anpi|ap|gif|stf|ipsec|ppp|docker|br-|virbr|veth|vboxnet"
          + "|lxc|lxdbr|cni|flannel|cali|podman|tun|tap|wg|zt|tailscale).*",
      Pattern.CASE_INSENSITIVE);

  /**
   * On Windows, where Java names interfaces eth0, wlan1, ..., what the adapter is (Java's display
   * name there): Hyper-V's, VMware's, VirtualBox's and other virtual adapters, VPNs' (TAP, Wintun,
   * WireGuard, ZeroTier, Tailscale), loopback and Bluetooth ones. release/breadboard.bat leaves out
   * the same.
   */
  static final Pattern HOST_ONLY_DESCRIPTION = Pattern.compile(
      ".*(virtual|vmware|virtualbox|hyper-v|vethernet|tap-|wintun|wireguard|zerotier|tailscale|loopback|bluetooth).*",
      Pattern.CASE_INSENSITIVE);

  /**
   * An address no packet ever goes to (TEST-NET-1, RFC 5737), outside every network this computer is
   * on: the system sends to it, if it did, from the address of the default route's interface.
   */
  private static final byte[] ELSEWHERE = {(byte) 192, 0, 2, 1};

  /** One network interface, as far as choosing addresses goes */
  static final class Nic {
    final String name;
    /** What it is (Java's display name: on Windows the adapter's description), or null */
    final String description;
    final int index;
    /** Up, and neither loopback nor point-to-point */
    final boolean usable;
    final List<Inet4Address> addresses;

    Nic(String name, String description, int index, boolean usable, List<Inet4Address> addresses) {
      this.name = name;
      this.description = description;
      this.index = index;
      this.usable = usable;
      this.addresses = addresses;
    }

    boolean hostOnly() {
      return HOST_ONLY_NAME.matcher(name).matches()
          || (description != null && HOST_ONLY_DESCRIPTION.matcher(description).matches());
    }
  }

  private LanAddresses() {}

  /** http://&lt;address&gt;&lt;port&gt; for each address ({@code port}: ":9000", or "" for none) */
  static List<String> urls(String port) {
    List<String> urls = new ArrayList<String>();
    for (Inet4Address address : choose(interfaces(), outward())) {
      urls.add("http://" + address.getHostAddress() + port);
    }
    return urls;
  }

  /**
   * The addresses others reach this computer at, from its interfaces {@code nics}: those of the
   * interface that has {@code outward} (the address the default route sends from; null when there is
   * no default route) first, that one first among them, then those of the others, by their index.
   */
  static List<Inet4Address> choose(List<Nic> nics, InetAddress outward) {
    List<Inet4Address> chosen = new ArrayList<Inet4Address>();
    Nic routed = null;
    if (outward instanceof Inet4Address) {
      for (Nic nic : nics) {
        if (nic.usable && nic.addresses.contains(outward)) {
          routed = nic;
          add(chosen, (Inet4Address) outward);
          for (Inet4Address address : nic.addresses) {
            add(chosen, address);
          }
          break;
        }
      }
    }
    List<Nic> others = new ArrayList<Nic>(nics);
    Collections.sort(others, new Comparator<Nic>() {
      @Override
      public int compare(Nic a, Nic b) {
        return Integer.compare(a.index, b.index);
      }
    });
    for (Nic nic : others) {
      if (nic != routed && nic.usable && !nic.hostOnly()) {
        for (Inet4Address address : nic.addresses) {
          add(chosen, address);
        }
      }
    }
    return chosen;
  }

  private static void add(List<Inet4Address> chosen, Inet4Address address) {
    if (!address.isLoopbackAddress() && !address.isLinkLocalAddress() && !address.isAnyLocalAddress()
        && !address.isMulticastAddress() && !chosen.contains(address)) {
      chosen.add(address);
    }
  }

  /** This computer's interfaces (none when they can't be listed) */
  static List<Nic> interfaces() {
    List<Nic> nics = new ArrayList<Nic>();
    try {
      for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        List<Inet4Address> addresses = new ArrayList<Inet4Address>();
        for (InetAddress address : Collections.list(nic.getInetAddresses())) {
          if (address instanceof Inet4Address) {
            addresses.add((Inet4Address) address);
          }
        }
        boolean usable = nic.isUp() && !nic.isLoopback() && !nic.isPointToPoint() && !nic.isVirtual();
        nics.add(new Nic(nic.getName(), nic.getDisplayName(), nic.getIndex(), usable, addresses));
      }
    } catch (Exception e) {
      play.Logger.debug("Could not list this computer's network addresses: " + e);
    }
    return nics;
  }

  /**
   * The address this computer sends from to the internet, that of its default route's interface: the
   * one a UDP socket connected to an address outside its networks gets (connecting one sends
   * nothing). Null when there is no default route. (A channel's: on macOS, Java 8's DatagramSocket
   * doesn't really connect, and says 0.0.0.0.)
   */
  static InetAddress outward() {
    try (DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET)) {
      channel.connect(new InetSocketAddress(InetAddress.getByAddress(ELSEWHERE), 9));
      SocketAddress local = channel.getLocalAddress();
      if (local instanceof InetSocketAddress) {
        InetAddress address = ((InetSocketAddress) local).getAddress();
        return address == null || address.isAnyLocalAddress() ? null : address;
      }
    } catch (IOException | RuntimeException e) {
      // no default route (no network at all), or no IPv4
    }
    return null;
  }
}
