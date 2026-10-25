/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Isaac Au, a1901982- frames are now prepared by a multi-threaded FramePipeline (nested class at the
 *      bottom of this file) instead of on the JavaFX thread; frame processors are passed in, not hard coded
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic and audio scheduling. Decoding, frame processing and
 * image conversion are done by a {@link FramePipeline} on worker threads (nested class at the bottom of this file);
 * this class, on the JavaFX thread, only displays the frames the pipeline has prepared. It publishes frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    private final List<FrameProcessor> frameProcessors;
    // CHANGED to a thread-safe queue: the pipeline's decoder thread adds audio while the JavaFX thread writes it out.
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
    private FramePipeline pipeline; // NEW: the worker threads that prepare frames. Null when playback is not open.
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private volatile boolean audioOutputEnabled; // volatile: the decoder thread reads it too
    private boolean audioAvailable;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessor> frameProcessors, // NEW: the effects from the command line, in order
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = List.copyOf(frameProcessors); // CHANGED: was an empty list with the effects commented out
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            // CHANGED: only one thread may use the grabber, so stop the workers (this also throws away every frame they
            // hold from the old position), reposition, then start a fresh pipeline from the new position.
            pipeline.stop();
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            startPipeline();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            startPipeline(); // CHANGED: replaces prepareNextFrame(); started before the timer so the timer never sees null
            playbackTimer.start();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        preparedFrame = null;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    // NEW: replaces prepareNextFrame/readNextVideoFrame, which did this work on the JavaFX thread one frame at a time.
    private void startPipeline() {
        pipeline = new FramePipeline(grabber, audioAvailable, this::newVideoInfo, this::queueAudio, frameProcessors, converter);
        pipeline.start();
    }

    // NEW: describes the video for the effects (the part of readNextVideoFrame that built the InfoVideo). It is called
    // once per pipeline, by the decoder thread, with the first frame.
    private InfoVideo newVideoInfo(Frame frame) {
        return new InfoVideo(
                mediaName(videoFile),
                totalVideoFrames,
                frame.imageWidth,
                frame.imageHeight,
                frame.imageDepth,
                frame.imageChannels,
                frame.imageStride,
                frameRate,
                intFrameRate,
                videoFrameDurationUs,
                grabber.getPixelFormat());
    }

    // NEW: the timing part of readNextVideoFrame. The clock now starts when the first frame reaches the display (not
    // when it was decoded, which is earlier by the time it spent in the pipeline); every later frame's due time follows
    // from its timestamp as before.
    private PreparedFrame toPreparedFrame(FramePipeline.ReadyFrame ready, long now) {
        if (firstTimestampUs == NO_SEEK_REQUEST) {
            firstTimestampUs = ready.timestampUs();
            playbackStartNs = now;
            if (pauseRequested) {
                pauseStartedNs = playbackStartNs;
            }
        }

        long relativeTimestampUs = Math.max(0, ready.timestampUs() - firstTimestampUs);
        return new PreparedFrame(
                ready.image(),
                ready.frameNumber(),
                ready.timestampUs(),
                logicalPlaybackBaseUs + relativeTimestampUs,
                playbackStartNs + relativeTimestampUs * 1_000L,
                ready.preparedAtNs());
    }

    // This is called on a heart beat by the GUI thread. CHANGED: the pipeline now does the hard work, so this just has
    // to (1) keep audio flowing, (2) take the next finished frame and (3) display it when it is due. It never blocks.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        // NEW: worker threads can't show errors, so they save them for us to report as the starter did.
        if (pipeline.failure() != null) {
            handlePlaybackError(pipeline.failure());
            return;
        }

        // Task (1)
        writeDueAudio(now);

        // Task (2). NEW: the pipeline keeps running ahead even while paused, so only take a frame when not paused (or
        // when the clock hasn't started yet: that is the first frame after a start or seek while paused, which is shown).
        if (preparedFrame == null && (!pauseRequested || firstTimestampUs == NO_SEEK_REQUEST)) {
            FramePipeline.ReadyFrame ready = pipeline.poll(); // never waits; null means "not ready yet"
            if (ready == FramePipeline.END) {
                finishPlayback(); // the last frame has been shown (the end marker travels behind it)
                return;
            }
            if (ready != null) {
                preparedFrame = toPreparedFrame(ready, now);
            }
        }

        // Task (3), unchanged: show the frame once it is due.
        if (preparedFrame != null && preparedFrame.targetTimeNs() <= now) {
            // We have a prepared frame ready to go and it is due (or just past due!) so get it up on screen ASAP!
            displayPreparedFrame(now);
        }
    }

    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        if (pipeline != null) { // NEW: stop the workers first; they use the grabber, converter and effects closed below
            pipeline.stop();
            pipeline = null;
        }
        preparedFrame = null;
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    // NEW: prepares frames on worker threads so the JavaFX thread only has to display them:
    //   decoder -> effect 1 -> effect 2 -> ... -> converter -> ready queue -> JavaFX thread (see pumpPlayback)
    // Each effect runs on its own thread, so different frames are in different effects at the same time and the time
    // per frame becomes that of the slowest stage instead of the sum of all stages. Why it is safe:
    //  - frames move between threads only through blocking queues, so each frame belongs to one thread at a time (no
    //    two effects draw on one frame, and no half-finished frame reaches the screen);
    //  - each queue has one thread putting in and one taking out, so frames and effects stay in order;
    //  - each effect object is used by its own thread only, so effects need no locking;
    //  - there are no locks, so no deadlock; stop() interrupts every thread, waking any that wait on a queue.
    private static final class FramePipeline {
        record ReadyFrame(Image image, int frameNumber, long timestampUs, long preparedAtNs) {
        }

        // Returned by poll() after the last frame.
        static final ReadyFrame END = new ReadyFrame(null, 0, 0, 0);

        private record Job(Frame frame, int frameNumber, long timestampUs, InfoVideo video) {
        }

        // Travels down the queues behind the last frame, so every thread finishes after handling every frame.
        private static final Job END_JOB = new Job(null, 0, 0, null);

        // Small bounded queues: a decoder that is faster than the slowest effect waits instead of filling memory with
        // frames (an HD frame is about 4.6MB).
        private static final int STAGE_QUEUE_CAPACITY = 1;
        private static final int READY_QUEUE_CAPACITY = 4;

        @FunctionalInterface
        private interface Body {
            void run() throws Exception;
        }

        private final BlockingQueue<ReadyFrame> ready = new ArrayBlockingQueue<>(READY_QUEUE_CAPACITY);
        private final List<Thread> threads = new ArrayList<>();
        private volatile Exception failure; // first error from any worker, for pumpPlayback to report

        // Frame buffers are reused, not allocated for every frame. Frame.clone() takes a native (non-Java) block of memory
        // of about 4.6MB for HD that the garbage collector NEVER frees: only Frame.close() does. Cloning every frame
        // without closing it ran the machine out of memory within seconds. So: the converter hands finished frames back
        // here, the decoder reuses them, and stop() closes every frame that was ever made.
        private final Queue<Frame> freeFrames = new ConcurrentLinkedQueue<>();
        private final List<Frame> allFrames = Collections.synchronizedList(new ArrayList<>());

        FramePipeline(
                FFmpegFrameGrabber grabber,
                boolean audioAvailable,
                Function<Frame, InfoVideo> videoInfoFactory,
                BiConsumer<Long, Frame> audioSink,
                List<FrameProcessor> processors,
                JavaFXFrameConverter converter) {
            // One thread each for the decoder, every effect (in command-line order) and the converter, each reading the
            // queue before it and writing the queue after it.
            BlockingQueue<Job> upstream = new ArrayBlockingQueue<>(STAGE_QUEUE_CAPACITY);
            BlockingQueue<Job> decoderOutput = upstream; // lambdas need a variable that is never reassigned
            addThread("decoder", () -> decode(grabber, audioAvailable, videoInfoFactory, audioSink, decoderOutput));

            for (FrameProcessor processor : processors) {
                BlockingQueue<Job> input = upstream;
                BlockingQueue<Job> output = new ArrayBlockingQueue<>(STAGE_QUEUE_CAPACITY);
                addThread("effect-" + processor.getClass().getSimpleName(), () -> applyEffect(processor, input, output));
                upstream = output;
            }

            BlockingQueue<Job> converterInput = upstream;
            addThread("converter", () -> convert(converter, converterInput));
        }

        void start() {
            threads.forEach(Thread::start);
        }

        // Interrupts every thread and waits for them, so nothing is still using the grabber or effects when we return.
        void stop() {
            threads.forEach(Thread::interrupt);
            for (Thread thread : threads) {
                try {
                    thread.join(2_000); // don't wait forever if FFmpeg is stuck
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            // Nothing is using the frames any more, so free their native memory (see freeFrames above).
            allFrames.forEach(Frame::close);
            allFrames.clear();
            freeFrames.clear();
        }

        // Next finished frame, END after the last one, or null if none is ready yet. Never waits.
        ReadyFrame poll() {
            return ready.poll();
        }

        Exception failure() {
            return failure;
        }

        private void addThread(String name, Body body) {
            Thread thread = new Thread(() -> {
                try {
                    body.run();
                } catch (InterruptedException e) {
                    // stop() was called; nothing to report
                } catch (Exception e) {
                    failure = e;
                } catch (Error e) { // e.g. out of memory: report it too, rather than leaving playback frozen with no message
                    failure = new Exception(e);
                }
            }, name);
            thread.setDaemon(true); // don't keep the program alive after the window closes
            threads.add(thread);
        }

        // The only thread that reads the grabber (it is not thread-safe). Does what readNextVideoFrame used to.
        private void decode(
                FFmpegFrameGrabber grabber,
                boolean audioAvailable,
                Function<Frame, InfoVideo> videoInfoFactory,
                BiConsumer<Long, Frame> audioSink,
                BlockingQueue<Job> output) throws Exception {
            InfoVideo video = null;
            Frame frame;
            while ((frame = audioAvailable ? grabber.grab() : grabber.grabImage()) != null) {
                long timestampUs = grabber.getTimestamp();
                if (audioAvailable && frame.samples != null) {
                    audioSink.accept(timestampUs, frame);
                }
                if (frame.image == null) {
                    continue;
                }
                if (video == null) {
                    video = videoInfoFactory.apply(frame);
                }
                // A copy is essential: the grabber reuses this Frame and overwrites it on the next grab, while the
                // effects (on other threads) are still working on this one. put() waits while the next queue is full.
                output.put(new Job(copyOf(frame), grabber.getFrameNumber(), timestampUs, video));
            }
            output.put(END_JOB);
        }

        // Copies a grabbed frame into a reused buffer, or makes a new one if none is free yet (only at the start: the
        // number of frames in flight is limited by the queue sizes, so only that many are ever made).
        private Frame copyOf(Frame source) {
            Frame copy = freeFrames.poll();
            ByteBuffer from = (ByteBuffer) source.image[0];
            if (copy == null || ((ByteBuffer) copy.image[0]).limit() != from.limit()) { // none free, or a different size
                copy = source.clone();
                allFrames.add(copy);
                return copy;
            }
            ByteBuffer to = (ByteBuffer) copy.image[0];
            from.rewind();
            to.rewind();
            to.put(from);
            from.rewind();
            to.rewind();
            return copy;
        }

        // One of these per effect. It is the only thread that ever touches this effect object.
        private void applyEffect(FrameProcessor processor, BlockingQueue<Job> input, BlockingQueue<Job> output)
                throws Exception {
            Job job;
            do {
                job = input.take();
                if (job != END_JOB) {
                    if (processor.videoInfo != job.video()) { // first frame of this pipeline: initialise the effect
                        processor.initialise(job.video());
                    }
                    processor.process(job.frame(), new InfoFrame(job.frameNumber(), job.timestampUs()));
                }
                output.put(job); // hand the frame on and never touch it again
            } while (job != END_JOB);
        }

        // The image conversion used to run on the JavaFX thread and is slow for HD. The converter keeps no state and
        // makes a new Image each call, so it is safe to run here.
        private void convert(JavaFXFrameConverter converter, BlockingQueue<Job> input) throws Exception {
            Job job;
            while ((job = input.take()) != END_JOB) {
                Image image = converter.convert(job.frame());
                freeFrames.add(job.frame()); // the pixels have been copied into the Image, so the buffer can be reused
                ready.put(new ReadyFrame(image, job.frameNumber(), job.timestampUs(), System.nanoTime()));
            }
            ready.put(END);
        }
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}