BEGIN {
    begun = 0
    done = 0
}

{
    if ($0 ~ "ENDEND") done = 1
    if (begun && !done) print $0
    if ($0 ~ "STARTSTART") begun = 1
}

