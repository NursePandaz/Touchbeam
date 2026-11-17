#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/tests
find tests -name '*.java' > build/test-sources.txt
printf '%s\n' app/src/main/java/dev/beam/classic/Wire.java app/src/main/java/dev/beam/classic/BeamTransport.java >> build/test-sources.txt
printf '%s\n' app/src/main/java/dev/beam/classic/FileProtocol.java app/src/main/java/dev/beam/classic/StagedFile.java >> build/test-sources.txt
printf '%s\n' app/src/main/java/dev/beam/classic/SharedContent.java >> build/test-sources.txt
printf '%s\n' app/src/main/java/dev/beam/classic/BatchProtocol.java app/src/main/java/dev/beam/classic/StagedBatch.java >> build/test-sources.txt
printf '%s\n' app/src/main/java/dev/beam/classic/SessionGate.java app/src/main/java/dev/beam/classic/NfcSession.java >> build/test-sources.txt
printf '%s\n' app/src/main/java/dev/beam/classic/DiscoveryQueue.java app/src/main/java/dev/beam/classic/PairingGuard.java >> build/test-sources.txt
printf '%s\n' app/src/main/java/dev/beam/classic/ReceiveConsent.java >> build/test-sources.txt
printf '%s\n' app/src/main/java/dev/beam/classic/TapOffer.java app/src/main/java/dev/beam/classic/TapChannel.java >> build/test-sources.txt
javac --release 8 -encoding UTF-8 -d build/tests @build/test-sources.txt
java -cp build/tests dev.beam.classic.BeamTests
