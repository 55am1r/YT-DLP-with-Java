import java.io.*;
import java.util.Scanner;

public class SongDownloader {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Welcome to the Song Downloader by PredatorFx!");
        System.out.print("Enter the URL of the song you want to download: ");
        String songUrl = scanner.nextLine(); // Replace with actual URL
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "C:\\Users\\shaik\\AppData\\Local\\Microsoft\\WinGet\\Packages\\yt-dlp.yt-dlp_Microsoft.Winget.Source_8wekyb3d8bbwe\\yt-dlp.exe",
                    "-x",
                    "--audio-format", "mp3",
                    "--audio-quality", "0",
                    "--embed-thumbnail",
                    "--add-metadata",
                    "-o",
                    System.getProperty("user.home")
                            + "/Downloads/Songs/%(title)s_%(uploader)s.%(ext)s",
                    songUrl);

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
}