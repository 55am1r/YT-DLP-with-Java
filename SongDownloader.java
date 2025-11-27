
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;
import java.util.regex.*;

public class SongDownloader {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        boolean listDownloadChoice = false;
        System.out.println("\n----->Welcome to the Song Downloader by PredatorFx!<------\n");
        System.out.print("Enter URL to download: ");
        String userUrl = scanner.nextLine();
        if (isUrlSupportedByYtDlp(userUrl)) {
            if (urlCheckToDownloadMusic(userUrl)) {
                downloadAudio(userUrl, listDownloadChoice);
            } else {
                Pattern pattern = Pattern.compile("[?&]list=");
                Matcher matcher = pattern.matcher(userUrl);
                if (matcher.find()) {
                    System.out.print("\n ***Your link contains list of Videos***\n");

                    System.out.print(
                            "\nDo you want to download All the Videos in the list?\n(Press Y for Yes, N for No): ");
                    while (true) {
                        String userChoice = scanner.nextLine().trim().toLowerCase();
                        if (userChoice.equals("y")) {
                            listDownloadChoice = true;
                            break;
                        } else if (!userChoice.equals("n")) {
                            System.out.print("\nInvalid choice. Please enter 'Y' for Yes or 'N' for No: ");
                            continue;
                        }
                        break; // Exit the loop if 'N' is chosen
                    }
                }
                System.out.print("\nWant to download a Song or Video?\n(Press A for Audio, V for Video): ");
                while (true) {
                    String choice = scanner.nextLine().trim().toUpperCase();
                    if (choice.equals("A")) {
                        downloadAudio(userUrl, listDownloadChoice);
                        break;
                    } else if (choice.equals("V")) {
                        downloadVideo(userUrl, listDownloadChoice);
                        break;
                    } else {
                        System.out.println("\nInvalid choice. Please enter 'A' for Audio or 'V' for Video.");
                    }
                }
            }
        } else {
            System.out.print(
                    "\nThe URL you entered is not supported by yt-dlp. Please try again with a valid YouTube URL.");

            System.out.println("Exiting the program. Goodbye!");
        }

    }

    public static boolean isUrlSupportedByYtDlp(String url) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "yt-dlp", "--flat-playlist", "--simulate", url);
            builder.redirectErrorStream(true);
            Process process = builder.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("ERROR:")) {
                    System.out.println("Not supported: " + line);
                    return false;
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0;

        } catch (Exception e) {
            System.err.println("Failed to check URL: " + e.getMessage());
            return false;
        }
    }

    public static boolean urlCheckToDownloadMusic(String userUrl) {
        try {
            URI uri = URI.create(userUrl);
            URL url = uri.toURL(); // still uses toURL safely
            if (url.getHost().contains("music.youtube")) {
                return true;
            }
            return false;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static String getBaseYouTubeUrl(String userUrl) {
        int index = userUrl.indexOf("list");
        if (index != -1) {
            // Move back 1 char to include '?' or '&' before "list"
            int start = index - 1;
            while (start > 0 && userUrl.charAt(start) != '?' && userUrl.charAt(start) != '&') {
                start--;
            }
            return userUrl.substring(0, start);
        }
        return userUrl; // return as-is if '&' not found
    }

    public static List<String> getAvailableResolutions(String videoUrl) {
        List<String> resolutions = new ArrayList<>();

        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "yt-dlp",
                    "--list-formats",
                    videoUrl);
            builder.redirectErrorStream(true);
            Process process = builder.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            Pattern resolutionPattern = Pattern.compile("\\b(\\d{2,5}x\\d{2,5})\\b");

            while ((line = reader.readLine()) != null) {
                if (line.contains("video only") || line.contains("audio only")) {
                    // parse video formats only
                    Matcher matcher = resolutionPattern.matcher(line);
                    if (matcher.find()) {
                        String resolution = matcher.group(1);
                        if (!resolutions.contains(resolution)) {
                            resolutions.add(resolution);
                        }
                    }
                }
            }

            process.waitFor();

            // Sort in ascending order by height value
            resolutions.sort(Comparator.comparingInt(res -> Integer.parseInt(res.split("x")[1])));

        } catch (Exception e) {
            System.err.println("Error while fetching resolutions: " + e.getMessage());
        }

        return resolutions;
    }

    public static void downloadAudio(String userUrl, boolean listDownloadChoice) {
        if (!listDownloadChoice) {
            userUrl = getBaseYouTubeUrl(userUrl);
        }
        System.out.println("\nDownloading audio...");
        // Replace with actual URL
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "yt-dlp",
                    "-x",
                    "--audio-format", "mp3",
                    "--audio-quality", "0",
                    "--embed-thumbnail",
                    "--add-metadata",
                    "-o",
                    // Adjust your loaction here
                    System.getProperty("user.home")
                    + "/Downloads/%(title)s_%(uploader)s.%(ext)s",
                    userUrl);

            builder.redirectErrorStream(true);
            Process process = builder.start();
            // Read
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);

                // Wait for the process to c
            }
            int exitCode = process.waitFor();
            System.out.println("Download completed with exit code: " + exitCode);
        } catch (Exception e) {
            System.err.println("Error downloading song: " + e.getMessage());
        }
    }

    public static void downloadVideo(String userUrl, boolean listDownloadChoice) {
        List<String> result = getAvailableResolutions(userUrl);
        if (!listDownloadChoice) {
            userUrl = getBaseYouTubeUrl(userUrl);
        }
        while (true) {
            Scanner scanner = new Scanner(System.in);
            String format = "";
            String videoType = "";
            while (true) {
                System.out.println("\nAvailable Video Resolutions:");
                for (int i = 0; i < result.size(); i++) {
                    System.out.print((i + 1) + ". " + result.get(i) + "\t");
                }
                System.out.print(
                        "\n\nWhich Quality do you want to download : ");
                int qualityChoice = scanner.nextInt();
                format = qualityChoice > 0 && qualityChoice <= result.size() ? result.get(qualityChoice - 1).split("x")[1] : result.get(result.size() - 1).split("x")[1];
                // switch (qualityChoice) {
                //     case 1 -> format = "144";
                //     case 2 -> format = "240";
                //     case 3 -> format = "360";
                //     case 4 -> format = "480";
                //     case 5 -> format = "660";
                //     case 6 -> format = "1080";
                //     case 7 -> format = "1440";
                //     case 8 -> format = "2160";
                //     default -> {
                //         System.out.println("Invalid choice. Defaulting to highest quality available.");
                //         continue;
                //     }
                // }
                System.out.println(
                        "\nIn Which Video Type do you want to download?");
                System.out.print("1. MP4\t2. MKV\t3. WEBM-->(Your choice [defaulted to MP4]): ");
                int videoTypeChoice = scanner.nextInt();
                switch (videoTypeChoice) {
                    case 1 ->
                        videoType = "mp4";
                    case 2 ->
                        videoType = "mkv";
                    case 3 ->
                        videoType = "webm";
                    default -> {
                        System.out.println("Invalid choice. Defaulting to MP4.");
                        videoType = "mp4";
                    }

                }
                break;
            }
            System.out.println("Downloading video...");
            boolean formatError = false;
            // Replace with actual URL
            try {
                ProcessBuilder builder = new ProcessBuilder(
                        "yt-dlp",
                        "-f", "bv[height=" + format + "]+ba",
                        "--merge-output-format", videoType,
                        "--embed-thumbnail",
                        "--add-metadata", "--postprocessor-args", "ffmpeg_i:-c:v h264_nvenc -preset fast -b:v 6M",
                        "-o",
                        // Adjust your loaction here
                        System.getProperty("user.home")
                        + "/Downloads/%(title)s_%(uploader)s.%(ext)s",
                        userUrl);

                builder.redirectErrorStream(true);
                Process process = builder.start();
                // Read
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    if (line.contains("Requested format is not available")
                            || line.toLowerCase().contains("unknown format")
                            || line.toLowerCase().contains("merge") && line.toLowerCase().contains("failed")
                            || line.toLowerCase().contains("error") && line.toLowerCase().contains("format")) {
                        formatError = true;
                    }
                }

                // Wait for the process to complete
                int exitCode = process.waitFor();
                System.out.println("Download completed with exit code: " + exitCode);

                if (formatError || exitCode != 0) {
                    System.out.println(
                            "\nFormat or video type issue. Please select right format or type as per Youtube.");
                    continue; // retry
                }
                break; // Exit the loop after successful download
            } catch (Exception e) {
                System.err.println("Error downloading video: " + e.getMessage());
            }
        }
    }
}
