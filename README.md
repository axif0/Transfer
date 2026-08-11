# Transfer

A simple local file server app for Android. Download and upload files quickly across devices over Wi-Fi — no cables, no cloud.

<table>
  <tr>
    <td>
    	<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01.png" width="150px" />
    	<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02.png" width="150px" />
    	<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03.png" width="150px" />
    	<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04.png" width="150px" />
    	<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05.png" width="150px" />
    	<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/06.png" width="150px" />
    </td>
  </tr>
</table>

Transfer makes sharing files across your local network incredibly straightforward. Think of it as a temporary USB stick you can access from any computer or device with a web browser — but without the physical stick, powered by a clean and simple UI.

Basically, a better alternative to running `uploadserver` through Termux.

## The Problem It Solves

Imagine you need to transfer a file between your laptop and desktop — but you just want to send the file and move on:

* You don’t have a USB stick or cable handy
* You don’t want to configure SMB (enable/run Samba, then download a client)
* You don’t want to use the cloud — either because it’s slow, unprivate, or you simply don’t have easy access to it

## Key Features

* **Effortless LAN Sharing**: Once Transfer is active, it serves files from your chosen shared folder over HTTP. Any device on the same Wi-Fi can connect using a simple web address shown in the app.
* **Optional Internet Sharing**: Expose the same server through a temporary Cloudflare Quick Tunnel URL when you need to share beyond your LAN. Public links are **read-only** (download only).
* **Quick Settings tile & widget**: Start/stop the server from the notification shade or home screen.
* **Configurable Security**:
    * **IP Permissions**: By default, new devices attempting to connect trigger an "Allow/Deny" popup on your phone, giving you control over who accesses your files. This can be turned off for trusted networks.
    * **Password Protection**: For an added layer, you can secure access with a password (off by default).
* **Powerful CLI Access (curl-friendly)**:
    * Transfer works great with command-line tools. Upload files directly using `curl -T yourfile.txt <your-phone-ip>:8000`.Also,you can download files using`curl <phone-ip>:8000/yourfile.txt`.
* **Dual Browse UI**: Manage and access your shared files directly within the Transfer app on your Android device, or through the intuitive web interface on any connected computer.
* **Quick In-App Transfers**:
    * **Upload**: Easily select files from your phone’s storage to add them to the shared folder.
    * **Paste**: Paste text from your phone’s clipboard directly into a new `.txt` file in the shared folder with a single tap.

## How to get the app
#### Recommended ways:
1. from [IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/com.matanh.transfer) (use an F-Droid client like Neo Store, Droid-ify, or add IzzyOnDroid repo.)

  <a href="https://apt.izzysoft.de/fdroid/index/apk/com.matanh.transfer">
    <img alt="Get it on IzzyOnDroid" src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroidButtonGreyBorder_nofont.png" height="54">
  </a>

2. [from Google play!](https://play.google.com/store/apps/details?id=com.matanh.transfer) - new

#### Other ways
Directly from [github releases](https://github.com/matan-h/Transfer/releases) (and you can also point Obtainium to this link.)

## Getting Started

1. Install and open Transfer on your Android device.
2. Grant necessary permissions and select a folder you wish to share (suggestion: create a new folder called `Storage` in your home directory).
3. Tap "Start Server."
4. The app will display an IP address (e.g., `http://192.168.1.X:8000`).
5. Open this address in a web browser on any other device connected to the same Wi-Fi network.
6. You're in! If IP permissions are on (default), you'll get a prompt on your phone to allow the new device.

It's designed to be that simple. Enjoy your new wireless drive.

## Internet Sharing

Optional mode that exposes the **same** local Ktor server to the public Internet through a [Cloudflare Quick Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/) — no VPS, no domain, no port forwarding, works behind CGNAT.

```
Android Transfer
      |
      +-- Ktor local HTTP server  (LAN: http://192.168.x.x:8000)
      |
      +-- cloudflared
              |
              v
       Cloudflare Edge
              |
              v
           Internet  (https://*.trycloudflare.com)
```

### How it works

1. Start the server as usual (LAN still works exactly as before).
2. Tap **Start Internet Sharing** (password optional — set one in Settings if you want).
3. The app launches a packaged `cloudflared` process and shows the generated `https://….trycloudflare.com` URL.
4. Share/copy the link.
5. Tap **Stop Internet Sharing** to tear down only the tunnel (LAN keeps running), or **Stop Server** to stop both.

Internet Sharing uses Cloudflare Quick Tunnels. The generated hostname is **temporary** — it changes when you start a new tunnel. It is intended for temporary sharing, not permanent hosting.

### Requirements / notes

* Password protection is **optional** (recommended if the link might be shared widely).
* No public IPv4, port forward, domain, Cloudflare account, Termux, or VPS required.
* The phone (and Transfer’s foreground service) must stay running.
* Performance depends on your mobile/Wi‑Fi uplink; large downloads use the phone’s upload bandwidth.
* See [docs/internet-sharing.md](docs/internet-sharing.md) for architecture, binary packaging, and licensing (`cloudflared` is Apache-2.0).

## FAQ
### Why does my browser show an error?
If your browser displays errors like `ERR_SSL_PROTOCOL_ERROR`, `ERR_CONNECTION_CLOSED`, or `SSL_ERROR_RX_RECORD_TOO_LONG`, it's probably because you're trying to open the site using **HTTPS** instead of **HTTP**. To fix, change the URL from `https://...` to `http://....`


### Can I set a specific IP address to use every time?
Yes, you can set a [static IP](https://junipersys.com/support/article/14695) on your Android device.

### Can I use Transfer without an internet connection?
Yes, Transfer works over your local Wi-Fi network, so an internet connection is not required. However, all devices must be connected to the same local network. (Internet Sharing is a separate optional feature and does need Internet access on the phone.)

### What happens to files I share/upload to the Transfer
Transfer copy the files to the shared folder you set earlier.

### Can friends outside my Wi‑Fi access my files?
Only if you explicitly start **Internet Sharing**. The LAN server alone is not exposed to the Internet. A password in Settings is recommended but optional.

### What happens if you click `T0` in the app version screen
It becomes `T1` :)

## Planned changes

- [ ] add an option to change the port in the settings
- [ ] fallback to hotspot IP in the display.
- [x] automatically update the IP when Wifi changes
- [x] optional Internet Sharing via Cloudflare Quick Tunnel

## Contributing

Any contributions, bugs, or feedback are welcome!
Feel free to open an issue or PR.
To get started, look at the `CONTRIBUTING.md` file

## License
this repo is Licensed under the MIT license.

Packaged `cloudflared` binaries are Apache-2.0 — see `THIRD_PARTY_CLOUDFLARED.md`.
