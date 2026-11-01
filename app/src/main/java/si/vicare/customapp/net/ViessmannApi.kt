package si.vicare.customapp.net

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Viessmann API endpoints. Bodies are returned unparsed: discovery saves them verbatim, and
 * [si.vicare.customapp.feature.FeatureParser] parses features leniently.
 */
interface ViessmannApi {

    @GET("users/v1/users/me")
    suspend fun me(@Query("sections") sections: String = "identity"): Response<ResponseBody>

    @GET("iot/v2/equipment/installations")
    suspend fun installations(@Query("includeGateways") includeGateways: Boolean = true): Response<ResponseBody>

    @GET("iot/v2/equipment/gateways/")
    suspend fun gateways(): Response<ResponseBody>

    @GET("iot/v2/equipment/installations/{installationId}/gateways/{gatewaySerial}/devices/")
    suspend fun devices(
        @Path("installationId") installationId: String,
        @Path("gatewaySerial") gatewaySerial: String,
    ): Response<ResponseBody>

    @GET("iot/v2/features/installations/{installationId}/features")
    suspend fun installationFeatures(@Path("installationId") installationId: String): Response<ResponseBody>

    @GET("iot/v2/features/installations/{installationId}/gateways/{gatewaySerial}/features")
    suspend fun gatewayFeatures(
        @Path("installationId") installationId: String,
        @Path("gatewaySerial") gatewaySerial: String,
    ): Response<ResponseBody>

    @GET("iot/v2/features/installations/{installationId}/gateways/{gatewaySerial}/devices/{deviceId}/features")
    suspend fun deviceFeatures(
        @Path("installationId") installationId: String,
        @Path("gatewaySerial") gatewaySerial: String,
        @Path("deviceId") deviceId: String,
        /** Comma separated feature names; null returns every feature. */
        @Query("filter") filter: String? = null,
    ): Response<ResponseBody>

    /** Commands are POSTed to the absolute `uri` given in the feature's `commands` entry. */
    @POST
    suspend fun executeCommand(@Url uri: String, @Body body: RequestBody): Response<ResponseBody>

    @GET("service-documents/v3/error-database")
    suspend fun errorDatabase(
        @Query("materialNumber") materialNumber: String,
        /** Null returns every code known for the material number. */
        @Query("errorCode") errorCode: String?,
        @Query("countryCode") countryCode: String,
        @Query("languageCode") languageCode: String,
    ): Response<ResponseBody>
}
