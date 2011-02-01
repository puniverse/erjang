#!/bin/bash

OUTPUT_BASE="$1" # A base name

BASE_DIR=`dirname "$BASH_SOURCE"`"/../../.."

OUTPUT_FILE="$OUTPUT_BASE.csv"
OUTPUT_REL_FILE="$OUTPUT_BASE-relative.csv"

EJ_EXE="$BASE_DIR/ej"

CACHE_DIR="$BASE_DIR/boot-test-cache"

# Special cache location:
export HOME="$CACHE_DIR"

function error() {
    echo "*** $1" >&2
    exit 1
}

function measure() {
    local outfile="$1"; shift # Rest of arguments are passed to ej
    /usr/bin/time --format '%e\t%U\t%S\t%M' --output boot-stats.tmp1 \
      ./ej "$@" -noshell -sasl sasl_error_logger false -eval \
	'io:format("~b\n", [V || {T,_,V}<-erlang:system_info(allocated_areas), (T=='"'non_heap:Perm Gen'"' orelse T=='"'non_heap:PS Perm Gen'"')]), erlang:halt().' \
	< /dev/null > boot-stats.tmp2
    paste boot-stats.tmp1 boot-stats.tmp2 >> "$outfile"
}

# Prepare cache dir and ensure no old result files remain:
mkdir "$HOME"
rm boot-measurement-{empty,populated,interpreted}.dat 2>/dev/null

# Run and measure:
for ((i=1; i<=3; i++)) ; do
    # Test from empty cache:
    measure boot-measurement-empty.dat

    # Test from populated cache:
    measure boot-measurement-populated.dat

    # Test interpreted mode:
    measure boot-measurement-interpreted.dat +i

    # Clean up:
    rm "$CACHE_DIR/.erjang/"*.{jar,ja#} 2>/dev/null
done

function compute() {
    local filenamepart="$1" legend="$2"
    perl -We '
      BEGIN {@sum=0; $cnt=0; $legend=$ARGV[0];}

      while (<STDIN>) {
        my $i=0;
        foreach (split("\t")) {$sum[$i++] += $_;}
        $cnt++;
      }

      END {
        # Rearrange: Elapsed, user+system, user, system, footprint, permgen.
        @sum = ($sum[0], $sum[1]+$sum[2], $sum[1], $sum[2], $sum[3], $sum[4]/1024);
        @avg = map {$_/$cnt} @sum;
        print "\"$legend - Elapsed time\",\"$legend - User+system time\",\"$legend - User time\",\"$legend - System time\",\"$legend - Process size\",\"$legend - PermGen size\"\n";
        print (join(",",@avg)."\n");
      }
    ' "$legend" < boot-measurement-$filenamepart.dat > "$OUTPUT_BASE-$filenamepart.csv"
}

compute empty "Empty module cache"
compute populated "Populated module cache"
compute interpreted "Interpreted mode"

# Clean up:
rmdir "$CACHE_DIR"

