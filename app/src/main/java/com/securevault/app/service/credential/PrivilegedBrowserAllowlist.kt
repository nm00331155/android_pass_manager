package com.securevault.app.service.credential

object PrivilegedBrowserAllowlist {
    val browserPackages = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "org.mozilla.fenix",
        "org.mozilla.focus"
    )

    const val json = """
        {
          "apps": [
            {
              "type": "android",
              "info": {
                "package_name": "com.android.chrome",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.chrome.beta",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "DA:63:3D:34:B6:9E:63:AE:21:03:B4:9D:53:CE:05:2F:C5:F7:F3:C5:3A:AB:94:FD:C2:A2:08:BD:FD:14:24:9C"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.chrome.dev",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "90:44:EE:5F:0E:4B:BC:5E:21:DD:44:66:54:31:C4:EB:1F:1F:71:A3:27:16:A0:BC:92:7B:CB:B3:92:33:CA:BF"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.chrome.canary",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "20:19:DF:A1:FB:23:EF:BF:70:C5:BC:D1:44:3C:5B:EA:B0:4F:3F:2F:F4:36:6E:9A:C1:E3:45:76:39:A2:4C:FC"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.brave.browser",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "9C:2D:B7:05:13:51:5F:DB:FB:BC:58:5B:3E:DF:3D:71:23:D4:DC:67:C9:4F:FD:30:63:61:C1:D7:9B:BF:18:AC"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.brave.browser_beta",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "9C:2D:B7:05:13:51:5F:DB:FB:BC:58:5B:3E:DF:3D:71:23:D4:DC:67:C9:4F:FD:30:63:61:C1:D7:9B:BF:18:AC"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.brave.browser_nightly",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "9C:2D:B7:05:13:51:5F:DB:FB:BC:58:5B:3E:DF:3D:71:23:D4:DC:67:C9:4F:FD:30:63:61:C1:D7:9B:BF:18:AC"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.microsoft.emmx",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "01:E1:99:97:10:A8:2C:27:49:B4:D5:0C:44:5D:C8:5D:67:0B:61:36:08:9D:0A:76:6A:73:82:7C:82:A1:EA:C9"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.microsoft.emmx.beta",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "01:E1:99:97:10:A8:2C:27:49:B4:D5:0C:44:5D:C8:5D:67:0B:61:36:08:9D:0A:76:6A:73:82:7C:82:A1:EA:C9"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "com.sec.android.app.sbrowser",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8"
                  },
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "34:DF:0E:7A:9F:1C:F1:89:2E:45:C0:56:B4:97:3C:D8:1C:CF:14:8A:40:50:D1:1A:EA:4A:C5:A6:5F:90:0A:42"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "org.mozilla.firefox",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "A7:8B:62:A5:16:5B:44:94:B2:FE:AD:9E:76:A2:80:D2:2D:93:7F:EE:62:51:AE:CE:59:94:46:B2:EA:31:9B:04"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "org.mozilla.fenix",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "50:04:77:90:88:E7:F9:88:D5:BC:5C:C5:F8:79:8F:EB:F4:F8:CD:08:4A:1B:2A:46:EF:D4:C8:EE:4A:EA:F2:11"
                  }
                ]
              }
            },
            {
              "type": "android",
              "info": {
                "package_name": "org.mozilla.focus",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "62:03:A4:73:BE:36:D6:4E:E3:7F:87:FA:50:0E:DB:C7:9E:AB:93:06:10:AB:9B:9F:A4:CA:7D:5C:1F:1B:4F:FC"
                  }
                ]
              }
            }
          ]
        }
    """
}