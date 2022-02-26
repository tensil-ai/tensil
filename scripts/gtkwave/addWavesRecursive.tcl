# See LICENSE for license details.

# TCL srcipt that uses the TCL-enabled version of GTKWave to generate
# a .gtkw save file with all the signals and groups found in a .vcd
# file. You will need to have the TCL enabled version of GTKWave
# installed to use this, e.g.:
#
#   yaourt -S gtkwave-tcl-svn
#
# Usage (run this to generate the .gtkw, then open it in GTKWave):
#   gtkwave -S addWavesRecursive.tcl [VCD FILE] > [GTKW FILE]
#   gtkwave [VCD FILE] [GTKW FILE]
#-------------------------------------------------------------------------------

#--------------------------------------- Stack operations
# Push and pop as defined by Richard Suchenwirth [1]
#
# REFERENCES
#   [1] http://wiki.tcl.tk/3333
interp alias {} push {} lappend

proc pop name {
    upvar 1 $name stack
    set res [lindex $stack end]
    set stack [lreplace $stack [set stack end] end]
    set res
}

#--------------------------------------- Tree Traversal
# Functions for tree traversal as defined by Richard Suchenwirth [2].
# Here, we're dealing with a data structure to describe a tree that
# consists of nested lists. So, assume that we have the following HDL
# structure as can be gleaned from looking at all the nodes in a VCD
# file:
#
#   A
#   |-- A.clk
#   |-- A.reset
#   |-- B
#       |-- B.x
#       |-- B.y
#
# In a tree, this looks like:
#
#   {A clk reset {B x y}}
#
# I've borrowed the following functions from Suchenwirth for getting
# information about and traversing a tree composed of lists:
#   * `traverse` -- dumps out a flat list composed of all the indices
#     into the actual tree
#   * `fromRoot` -- dumps out the indices of all nodes from the root
#     to the specified node
#   * `absolutePath`: -- uses `fromRoot` to find all the nodes and
#     then returns a list of the names of all these nodes
#
# REFERENCES
#   [2] http://wiki.tcl.tk/8580
proc traverse {tree {prefix ""}} {
    set res {}
    if {[llength $tree]>1} {
        lappend res [concat $prefix 0] ;# content
        set i 0
        foreach child [lrange $tree 1 end] {
            eval lappend res [traverse $child [concat $prefix [incr i]]]
        }
    } else {set res [list $prefix]} ;# leaf
    set res
}

proc fromRoot index {
    set res {}
    set path {}
    foreach i $index {
        if $i {lappend res [concat $path 0]}
        lappend path $i
    }
    lappend res $index
}

proc absolutePath {tree index} {
    set res {}
    foreach i [fromRoot $index] {
        lappend res [lindex $tree $i]
    }
    set res
}

#--------------------------------------- New procedures for addWavesRecursive
# Procedure to construct a linear tree (terminology is probably wrong)
# of the nested list structure that we're using. This can then be
# relatively easily merged with an existing leafy tree.
proc constructTree {signal} {
    foreach node [lreverse [string map {. " "} $signal]] {
        if {[info exists tree]} {
            set tree [list $node $tree]
        } else {
            set tree $node
        }
    }
    return $tree
}

# Attaches a linear subtree to an existing tree. I use this to build
# up the full tree data structure. The tree and subtree must have the
# same root node. This recursive function looks at all the children of
# both the tree and the subtree and recurses if it finds a matching
# child. If it doesn't find a matching child, it will append the
# subtree to the current node.
proc merge {tree subtree} {
    set index 1
    set newtree {}
    foreach child [lrange $tree 1 end] {
        foreach subchild [lrange $subtree 1 end] {
            # If we find a match, then we recurse
            if {[string compare [lindex $child 0] [lindex $subchild 0]] == 0} {
                return [lreplace $tree $index $index [merge $child $subchild]]
            }
        }
        incr index
    }
    # We didn't find anything so we just append this list
    lappend tree [lindex $subtree 1]
    return $tree
}

#---------------------------------------- Main addWavesRecursive TCL script
# print out start marker for awk (kludge needed when using gtkwave with -O flag
# for output, as on MacOS)
puts "STARTSTART"

set nfacs [ gtkwave::getNumFacs ]
set dumpname [ gtkwave::getDumpFileName ]
set dmt [ gtkwave::getDumpType ]

# Some information is included in the GTKWave header, however this
# doesn't appear to have much effect on GTKWave. Generally, GTKWave
# will just ignore things it doesn't understand. Nevertheless, we
# default to using a coment syntax of "[*]":
# puts "\[*\] number of signals in dumpfile '$dumpname' of type $dmt: $nfacs"
# puts "\[dumpfile\] \"[file join [pwd] $dumpname]\""
# puts "\[dumpfile_size\] [file size [file join [pwd] $dumpname]]"
# puts "\[optimize_vcd\]"
# A .gtkw file has some additional meta information which we're not
# using:
# puts "\[savefile\]"
# puts "\[timestart\] 0"

# Populate a list called 'signals' with all the signals found in the
# design
set signals [list]
for {set i 0} {$i < $nfacs } {incr i} {
    set facname [ gtkwave::getFacName $i ]
    lappend signals "$facname"
}

# Based on what the signals that we found are, we need to figure out
# what is the top module. The following examines the first signal and
# rips out the first string before the first ".".
set tree [lindex [split [lindex $signals 0] .] 0]

# Now, construct a bare tree representation for each of the signals and
# merge them into a large, complete tree that represents the
# hierarchical structure of the entire design.
foreach signal $signals {
    # puts [constructTree $signal]
    set tree [merge $tree [constructTree $signal]]
}

# Dump out the number of signals that we found.
set num_added [ gtkwave::addSignalsFromList $signals ]
puts "\[*\] num signals added: $num_added"

# Dump additional configuration information
puts "\[sst_expanded\] 0"

# Having this tree, we can then generate a .gtkw configuration that we
# can load in with all the signals. GTKWave uses some special bit
# flags to tell it what type of signal we're dealing with. This is all
# documented internally in their "analyzer.h" file. However, all that
# we really care about are:
#
#        0x22: (right justified) | (hexadecimal format)
#        0x28: (right justified) | (binary format)
#    0xc00200: (group state)     | (TR_CLOSED_B)        | (TR_BLANK)
#   0x1401200: (group end)       | (TR_CLOSED_B)        | (TR_BLANK)
#
# Whenever we see a group, we need to explicitly set these before the
# group name. After the group, we need to revert to the default signal
# format 0x22 (or 0x28). We set the groups as closed because this
# seems to make the initial signal dump easier to look at.
# alternatively, the following group bits can be used to have the
# groups default to open:
#
#    0x800200: (group start)     | (TR_BLANK)
#   0x1000200: (group end)       | (TR_BLANK)
#
# We loop over all the nodes and dump out the group names (and GTKWave
# hex flags). Group names are pushed onto a stack when we find a new
# group so that they can be popped when we get to the end of the
# current module. To make this easier to visually parse, we throw in
# some comment lines indicating the beginning of a new group ("vvvv")
# and the end of a group ("^^^^").
set oldNode -1
set colorIncr 3
set colorCounter 3
set color 4
foreach node [traverse $tree] {
    # This is broken for corner cases. What you want to do here is to
    # walk up the oldNode until you hit a common parent with the new
    # node. Everytime you do this, you pop. You then traverse down the
    # new node until you hit a leaf. Every increase in depth is a push.

    # Transition out of a group if the oldNode is deeper than the new
    # node. We may have to jump out multiple levels here.
    set oldDepth [llength $oldNode]
    set newDepth [llength $node]
    for {set i 0} {$i < [llength $oldNode] - [llength $node]} {incr i} {
        puts "@1401200"
        puts "-[pop hier]"
        puts "@22"
        puts "\[*\]^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^"
    }
    # Weak check that will handle a node change of the same depth (or
    # different depth if we've just done some number of pops).
    if {(([llength $node] == [llength $oldNode]) &&
         ([lindex $node end-1] != [lindex $oldNode end-1])) ||
        (($i > 0) &&
         ([lindex $node end-1] != [lindex $oldNode end-[expr $i+1]]))} {
        puts "@1401200"
        puts "-[pop hier]"
        puts "@22"
        puts "\[*\]^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^"
    }
    # Transition into a group when we see a new initial leaf with an
    # index of 0.
    if {[lindex $node end] == 0} {
        puts "\[*\]vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv"
        puts "@c00200"
        puts "-[join [absolutePath $tree $node] .]"
        puts "@22"
        push hier [join [absolutePath $tree $node] .]
        set oldNode $node
	# Cycle through colors starting from green. Allowable color values are
	# 1 = red, 2 = orange, 3 = yellow, 4 = green, 5 = blue, 6 = indigo,
	# 7 = violet.
	if {$colorIncr == -4} {set colorIncr 3} else {set colorIncr -4}
	set colorCounter [expr [expr $colorCounter + $colorIncr] % 7]
	set color [expr $colorCounter + 1]
        continue
    }
    # Print the current signal name.
    set signalName "[lindex [absolutePath $tree $node] end]"
    if {[string match _* $signalName] == 0} {
        puts "\[color\] $color"
        puts "[join [absolutePath $tree $node] .]"
    }
    set oldNode $node
}
# We're done, but the stack may still have entries, i.e., there are
# unclosed groups. Dump all these to close everything.
foreach node $hier {
    puts "@1401200"
    puts "-[pop hier]"
    puts "@22"
    puts "\[*\]^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^"
}

# print end marker (needed when gtkwave is run with -O flag). See above
# comment about start marker
puts "ENDEND"

# We're done, so exit.
gtkwave::/File/Quit
