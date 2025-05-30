import java.io.*;
import java.net.URI;
import java.util.Scanner;

import java.net.URL;

public class SongDownloader {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("\n----->Welcome to the Song Downloader by PredatorFx!<------\n");
        while (true) {
            System.out.print("Enter URL to download: ");
            String userUrl = scanner.nextLine();
            if ((!userUrl.startsWith("http://") && !userUrl.startsWith("https://")) || userUrl.isEmpty()) {
                System.out.println("\nInvalid URL. Please enter a valid URL starting with http:// or https://");
                continue;
            } else {
                if (urlCheckToDownloadMusic(userUrl)) {
                    downloadAudio(userUrl);
                    break;
                } else {
                    System.out.print("\nWant to download a Song or Video?\n(Press A for Audio, V for Video): ");
                    String choice = scanner.nextLine().trim().toUpperCase();
                    if (choice.equals("A")) {
                        downloadAudio(userUrl);
                        break;
                    } else if (choice.equals("V")) {
                        downloadVideo(userUrl);
                        break;
                    } else {
                        System.out.println("\nInvalid choice. Please enter A for Audio or V for Video.");
                    }
                    break;
                }
            }

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
        return userUrl;  // return as-is if '&' not found
    }

    public static void downloadAudio(String userUrl) {
        userUrl = getBaseYouTubeUrl(userUrl);
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
                int exitCode = process.waitFor();
                System.out.println("Download completed with exit code: " + exitCode);
            }
        } catch (Exception e) {
            System.err.println("Error downloading song: " + e.getMessage());
        }
    }

    public static void downloadVideo(String userUrl) {
        userUrl = getBaseYouTubeUrl(userUrl);
        while (true) {
            Scanner scanner = new Scanner(System.in);
            String format = "";
            String videoType = "";
            while (true) {
                System.out.println(
                        "\nWhich Quality do you want to download?\n -->(Look in Youtube Quality Settings, Select the top quality available for your video)");
                System.out.print(
                        "1. 144p\t2. 240p\t3. 360p\t4. 480p\n5. 720p\t6. 1080p 7. 1440p 8. 2160p -->(Your choice): ");
                int qualityChoice = scanner.nextInt();
                switch (qualityChoice) {
                    case 1:
                        format = "144";
                        break;
                    case 2:
                        format = "240";
                        break;
                    case 3:
                        format = "360";
                        break;
                    case 4:
                        format = "480";
                        break;
                    case 5:
                        format = "720";
                        break;
                    case 6:
                        format = "1080";
                        break;
                    case 7:
                        format = "1440";
                        break;
                    case 8:
                        format = "2160";
                        break;
                    default:
                        System.out.println("Invalid choice. Defaulting to highest quality available.");
                        continue;
                }
                System.out.println(
                        "\nIn Which Video Type do you want to download?");
                System.out.print("1. MP4\t2. MKV -->(Your choice [defaulted to MP4]): ");
                int videoTypeChoice = scanner.nextInt();
                switch (videoTypeChoice) {
                    case 1:
                        videoType = "mp4";
                        break;
                    case 2:
                        videoType = "mkv";
                        break;
                    default:
                        System.out.println("Invalid choice. Defaulting to MP4.");
                        videoType = "mp4";

                }
                break;
            }
            System.out.println("Downloading video...");
            // Replace with actual URL
            try {
                ProcessBuilder builder = new ProcessBuilder(
                        "yt-dlp",
                        "-f", "bv[height=" + format + "]+ba",
                        "--merge-output-format", videoType,
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
                }

                // Wait for the process to complete
                int exitCode = process.waitFor();
                System.out.println("Download completed with exit code: " + exitCode);
                break; // Exit the loop after successful download
            } catch (Exception e) {
                System.err.println("Error downloading video: " + e.getMessage());
                continue;
            }
        }
    }
}
