import java.io.*;
import java.util.Scanner;

public class SongDownloader {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Welcome to the Song Downloader by PredatorFx!");
        System.out.print("Enter the URL of the song/video you want to download: ");
        String userUrl = scanner.nextLine(); // Replace with actual URL
        try {
            ProcessBuilder builder = new ProcessBuilder(
"yt-dlp",
                    "-x",
                    "--audio-format", "mp3",
                    "--audio-quality", "0",
                    "--embed-thumbnail",
                    "--add-metadata",
                    "-o",
                //Adjust your loaction here
                    System.getProperty("user.home")
                            + "/Downloads/Editing Tools/%(title)s_%(uploader)s.%(ext)s",
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
}
