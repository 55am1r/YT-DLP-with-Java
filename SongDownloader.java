import java.io.*;
import java.util.Scanner;

public class SongDownloader {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Welcome to the Song Downloader by PredatorFx!");
        while (true) {
            System.out.println("Want to download a Song or Video?\n(Press A for Audio, V for Video): ");
            String choice = scanner.nextLine().trim().toUpperCase();
            if (choice.equals("A")) {
                System.out.print("Enter the URL of the Song to download: ");
                String userUrl = scanner.nextLine();
                downloadAudio(userUrl);
                break;
            } else if (choice.equals("V")) {
                System.out.print("Enter the URL of the Video to download: ");
                String userUrl = scanner.nextLine();
                downloadVideo(userUrl);
                break;
            } else {
                System.out.println("Invalid choice. Please enter A for Audio or V for Video.");
            }
        }
    }

    public static void downloadAudio(String userUrl) {
        System.out.println("Downloading audio...");
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
        Scanner scanner = new Scanner(System.in);
        String format = "";
        String videoType = "";
        while (true) {
            System.out.println(
                    "Which Quality do you want to download? \n (Look in Youtube Quality Settings, Select the top quality available for your video)");
            System.out.println(
                    "1. 144p\t2. 240p\t3. 360p\t4. 480p\t5. 720p\t6. 1080p\t7. 1440p\t8. 2160p (Your choice): ");
            int qualityChoice = scanner.nextInt();
            System.out.println(
                    "In Which Video Type do you want to download? ");
            System.out.println("1. MP4\t2. MKV\t3. WEBM\t4. MOV (Your choice [defaulted to MP4]): ");
            int videoTypeChoice = scanner.nextInt();
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
            }
            switch (videoTypeChoice) {
                case 1:
                    videoType = "mp4";
                    break;
                case 2:
                    videoType = "mkv";
                    break;
                case 3:
                    videoType = "webm";
                    break;
                case 4:
                    videoType = "mov";
                    break;
                default:
                    System.out.println("Invalid choice. Defaulting to MP4.");
                    videoType = "mp4";
            }
            if (format.isEmpty()) {
                continue;
            } else {
                break;
            }
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
        } catch (Exception e) {
            System.err.println("Error downloading video: " + e.getMessage());
        }
    }
}
