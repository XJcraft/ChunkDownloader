package org.xjcraft.chunkdownloader;

import com.microsoft.graph.authentication.IAuthenticationProvider;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.DriveItemVersion;
import com.microsoft.graph.requests.GraphServiceClient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import okhttp3.Request;
import org.xjcraft.chunkdownloader.config.AzureConfig;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class OneDriveUtil {

    private static final String AUTHORITY = "https://login.microsoftonline.com/" + AzureConfig.config.getTenantId() + "/oauth2/v2.0/token";
    private static final String SCOPE = "https://graph.microsoft.com/.default";

//    public static void main(String[] args) {
//        String fileName = "map/MainLand/region/r.8.-6.mca";
//        String fileDownloadUrl = getFileDownloadUrl(fileName);
//        System.out.println("Download URL: " + fileDownloadUrl);
//    }


    public static String getFileDownloadUrl(String fileName) {
        IAuthenticationProvider authProvider = url -> CompletableFuture.supplyAsync(OneDriveUtil::getAccessToken);

        GraphServiceClient<Request> graphClient = GraphServiceClient
                .builder()
                .authenticationProvider(authProvider)
                .buildClient();

        DriveItem driveItem = graphClient
                .users(AzureConfig.config.getUserId())
                .drive()
                .root()
                .itemWithPath("/" + fileName)
                .buildRequest()
                .get();

        return driveItem.additionalDataManager().get("@microsoft.graph.downloadUrl").getAsString();
    }
    public static List<VersionUrl> getFileVersionDownloadUrls(String fileName) {
        IAuthenticationProvider authProvider = url -> CompletableFuture.supplyAsync(OneDriveUtil::getAccessToken);

        GraphServiceClient<Request> graphClient = GraphServiceClient
                .builder()
                .authenticationProvider(authProvider)
                .buildClient();

        // 获取文件的 DriveItem
        DriveItem driveItem = graphClient
                .users(AzureConfig.config.getUserId())
                .drive()
                .root()
                .itemWithPath("/" + fileName)
                .buildRequest()
                .get();

        // 获取该文件的历史版本
        List<DriveItemVersion> versions = graphClient
                .users(AzureConfig.config.getUserId())
                .drive()
                .items(driveItem.id)
                .versions()
                .buildRequest()
                .get()
                .getCurrentPage();

        // 收集每个版本的下载URL
        List<VersionUrl> downloadUrls = new ArrayList<>();
        int i = 10;
        for (DriveItemVersion version : versions) {
            String downloadUrl = version
                    .additionalDataManager()
                    .get("@microsoft.graph.downloadUrl")
                    .getAsString();
            OffsetDateTime dateTime = version.lastModifiedDateTime;

            downloadUrls.add(new VersionUrl(dateTime.toString(),downloadUrl));
            i--;
            if (i <= 0) {
                break;
            }
        }

        return downloadUrls;
    }


    private static String getAccessToken() {
        try {
            String tokenEndpoint = AUTHORITY;
            String payload = "client_id=" + AzureConfig.config.getClientId()
                    + "&scope=" + SCOPE
                    + "&client_secret=" + AzureConfig.config.getClientSecret()
                    + "&grant_type=client_credentials";

            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
            okhttp3.RequestBody body = okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/x-www-form-urlencoded"),payload);
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(tokenEndpoint)
                    .post(body)
                    .build();

            okhttp3.Response response = client.newCall(request).execute();
            String responseBody = response.body().string();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> map = mapper.readValue(responseBody, java.util.Map.class);

            return map.get("access_token").toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class VersionUrl{
        String time;
        String url;
    }
}
