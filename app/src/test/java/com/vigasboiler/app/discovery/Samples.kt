package com.vigasboiler.app.discovery

/**
 * Synthetic responses shaped like the equipment and features APIs. Replace with the redacted
 * real dump in docs/feature-dump/ once it exists.
 */
object Samples {
    const val INSTALLATION_ID = "1234567"
    const val GATEWAY_SERIAL = "7637415012345678"
    const val BOILER_SERIAL = "7723181102345678"
    const val EQUIPMENT_NUMBER = "7931234567890123"
    const val CRM_ID = "0PK1i00000AbCdEfGH"

    val installations = """
        {"data":[{"id":$INSTALLATION_ID,"description":"Home of Jane Doe",
          "address":{"street":"Main street","houseNumber":"1","zip":"1000","city":"Kranj","country":"si",
                     "geolocation":{"latitude":46.05,"longitude":14.5,"timeZone":"Europe/Ljubljana"}},
          "gateways":[{"serial":"$GATEWAY_SERIAL","version":"1.2.3","installationId":$INSTALLATION_ID,
                       "devices":[{"gatewaySerial":"$GATEWAY_SERIAL","id":"0","modelId":"E3_Vitodens_100_0421"}]}]}],
         "cursor":{"next":""}}
    """.trimIndent()

    val gateways = """{"data":[{"serial":"$GATEWAY_SERIAL","installationId":$INSTALLATION_ID,"gatewayType":"E3"}]}"""

    val devices = """
        {"data":[{"gatewaySerial":"$GATEWAY_SERIAL","id":"gateway","modelId":"Heatbox1"},
                 {"gatewaySerial":"$GATEWAY_SERIAL","id":"0","modelId":"E3_Vitodens_100_0421"}]}
    """.trimIndent()

    val me = """
        {"id":"a1b2c3d4-e5f6-7890-abcd-ef0123456789","loginId":"jane@example.com","userState":"ACTIVE","gender":"FEMALE",
         "name":{"firstName":"Jane","familyName":"Doe"},
         "address":{"addressline1":"Main street 1","addressline2":"5","street":"Main street 1","houseNumber":"1","postalCode":"4000","city":"Kranj","countryCode":"si"},
         "contacts":{"telephone":"051123456","email":"jane@example.com"},
         "properties":[{"name":"crmId","value":"$CRM_ID"}],
         "languageCode":"en","locale":"en-si"}
    """.trimIndent()

    val deviceFeatures = """
        {"data":[
          {"feature":"device.etn","isEnabled":true,"properties":{"value":{"type":"string","value":"$EQUIPMENT_NUMBER"}},"commands":{}},
          {"feature":"heating.boiler.serial","gatewayId":"$GATEWAY_SERIAL","deviceId":"0","isEnabled":true,
           "properties":{"value":{"type":"string","value":"$BOILER_SERIAL"}},"commands":{}},
          {"feature":"heating.circuits.0.operating.modes.active","gatewayId":"$GATEWAY_SERIAL","deviceId":"0","isEnabled":true,
           "properties":{"value":{"type":"string","value":"heating"}},
           "commands":{"setMode":{"uri":"https://api.viessmann-climatesolutions.com/iot/v2/features/installations/$INSTALLATION_ID/gateways/$GATEWAY_SERIAL/devices/0/features/heating.circuits.0.operating.modes.active/commands/setMode",
                                  "name":"setMode","isExecutable":true,
                                  "params":{"mode":{"type":"string","required":true,"constraints":{"enum":["heating","standby"]}}}}}},
          {"feature":"heating.circuits.0.name","isEnabled":true,"properties":{"name":{"type":"string","value":"Radiators"}},"commands":{}},
          {"feature":"heating.configuration.houseLocation","isEnabled":true,
           "properties":{"latitude":{"type":"number","value":46.2391,"unit":"degree"},"longitude":{"type":"number","value":14.3556,"unit":"degree"}},"commands":{}},
          {"feature":"heating.sensors.temperature.outside","isEnabled":true,"properties":{"value":{"type":"number","value":7.4,"unit":"celsius"}},"commands":{}}
        ]}
    """.trimIndent()
}
