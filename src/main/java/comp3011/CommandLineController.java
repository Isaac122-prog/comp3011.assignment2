/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Isaac Au, a1901982 - added frame processor options and stacked short options
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;

    // NEW: the effects requested on the command line, kept in the order typed. A list (not a set) because the order
    // is the order the effects are applied in, and because the same effect may be repeated (-ss scratches twice).
    private final List<Effect> frameProcessorTypes = new ArrayList<>();

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    /**
     * NEW: turns the recorded options into real effect objects for the app to hand to the player. Parsing only
     * records the options (so it has no side effects); the objects are created here, once, when they are needed.
     * Every call to create() makes a fresh object, so a repeated option gives two independent effects.
     */
    public List<FrameProcessor> createFrameProcessors() {
        return frameProcessorTypes.stream().map(Effect::create).toList();
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        // CHANGED: loops over the expanded list instead of args, so that stacked options like -jfd are seen as -j -f -d.
        for (String arg : expandStackedOptions()) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                helpRequested = true;
            } else if ("-a".equals(arg) || "--audio".equals(arg)) {
                audioRequested = true;
            } else if ("-x".equals(arg) || "--maximise".equals(arg)) {
                maximiseRequested = true;
            } else if ("-1".equals(arg) || "--monitor-1".equals(arg)) {
                setDisplayId(1);
            } else if ("-2".equals(arg) || "--monitor-2".equals(arg)) {
                setDisplayId(2);
            } else if (Effect.fromArgument(arg).isPresent()) {
                // NEW: any option matching an effect in the list at the bottom of this file (short or long form) is
                // added to the end of the list. This keeps command-line order and allows repeats.
                frameProcessorTypes.add(Effect.fromArgument(arg).get());
            } else if (arg.startsWith("-")) {
                errorMessage = "Unknown option: " + arg;
            } else {
                videoFiles.add(arg);
            }
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }

    // NEW: turns stacked short options such as -jfd into -j -f -d. Doing this first means the existing if/else chain in
    // parse() does not need to know about stacking at all. Long options (--like-this) and the video file name are left
    // alone, and a lone two-character option such as -n is already single so it passes straight through.
    private List<String> expandStackedOptions() {
        List<String> expanded = new ArrayList<>();
        for (String arg : args) {
            if (arg.length() > 2 && arg.startsWith("-") && !arg.startsWith("--")) {
                for (char flag : arg.substring(1).toCharArray()) {
                    expanded.add("-" + flag);
                }
            } else {
                expanded.add(arg);
            }
        }
        return expanded;
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        // NEW: the extra help paragraphs the specification asks for. The option lines are generated from the Effect list
        // below, so the help text can never disagree with what the parser actually accepts.
        System.out.println();
        System.out.println("Frame processors:");
        for (Effect type : Effect.values()) {
            System.out.println(type.helpLine());
        }
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }

    /**
     * NEW: the one list of every effect that can be requested from the command line, in help text order.
     *
     * Why a list like this: the short flag, long flag, help text and "how to make one" for each effect live together on
     * a single line, so the parser, the help text and the object creation can never get out of step with each other.
     *
     * TO ADD A NEW EFFECT: write the FrameProcessor subclass and add one line here. Nothing else needs to change.
     *
     * Why create() makes a NEW object each time: effects keep their own working data and take their random seed from
     * how many of that type have been created so far. Sharing one object for a repeated option (-ss) would make the two
     * scratches identical and would let two threads use the same object.
     *
     * Why it is nested inside this class: it is only used here, and it keeps the change to existing files only.
     */
    private enum Effect {
        NUMBER('n', "number-frames", "Render the frame number onto each frame", FrameNumberer::new),
        SCRATCH('s', "scratch-frames", "Render vertical film scratches", FrameScratcher::new),
        FLICKER('f', "flicker-frames", "Randomly dim frames", FrameFlickerer::new),
        BLACK_AND_WHITE('w', "black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
        YELLOW('y', "yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
        VIGNETTE('v', "vignette-frames", "Darken the frame edges", FrameVignetter::new),
        DUST('d', "dust-frames", "Render dust and hair marks", FrameDuster::new),
        JITTER('j', "jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
        MOTTLE('m', "mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
        BLEED('b', "bleed-frames", "Bleed light into frames", FrameBleeder::new),
        PEPPER('p', "pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new);

        private final char shortFlag;
        private final String longFlag;
        private final String description;
        private final Supplier<FrameProcessor> factory;

        Effect(char shortFlag, String longFlag, String description, Supplier<FrameProcessor> factory) {
            this.shortFlag = shortFlag;
            this.longFlag = longFlag;
            this.description = description;
            this.factory = factory;
        }

        String helpLine() {
            return String.format("  %-22s %s", "-" + shortFlag + ", --" + longFlag, description);
        }

        FrameProcessor create() {
            return factory.get();
        }

        /** Finds the effect for an argument such as "-n" or "--number-frames". */
        static Optional<Effect> fromArgument(String arg) {
            return Arrays.stream(values())
                    .filter(type -> arg.equals("-" + type.shortFlag) || arg.equals("--" + type.longFlag))
                    .findFirst();
        }
    }
}