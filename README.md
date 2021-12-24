# ghidra-ExportDwardELFSymbols
A format agnostic script to export an ELF file with DWARF symbols from a Ghidra program

This script was heavilly inspired by CeSeNA's [ghidra2dwarf](https://github.com/cesena/ghidra2dwarf) script, but the main difference is their script append informations to an existing ELF while this script is to generate a new one from scratch and figure out the proper format.

## Motivations
This script was made because there currently isn't a good way to work with relatively esotheric debugging targets for ghidra (like console emulators) while being able to not only have symbols, but have access to debugging features such as `nexti` (step over). The `nexti` command has trouble to works on these targets (it would act as a `stepi`) because GDB doesn't have enough informations to unwind the stack and after research, it was found that giving minimal symbols to GDB (mainly where functions are) is enough for it to figure out how to decide that a new stack frame was entered

## Features
This script was made to generate the ELF from scratch: it doesn't care how the program is formatted, it simply spit DWARF informations from what Ghidra knows.

Here is how this script adapts the ELF depending on the target:
- 32 bit or 64 bit are supported (will take the proper ELF format variant and assign the header's machine correctly).
- Either endianness (little or big) are supported and will be assigned correctly to the ident of the ELF header.
- The following CPU languages are assigned to the machine field of the ELF header: x86, PowerPC, ARM and MIPS (default to x86 if not from this list).
- The entry point is taken from the first entry point refference in the program.

Currently, only the names, entry points and the ends address of functions are exported. 

## Installation instructions
1. Download the lattest zip file  from the release page and extract the zip to a desired location.
2. Open Ghidra. On the main window, go to Edit -> Plugin Path... then click "Add Jar..." and select the libdwarf.jar that is inside the zip you extracted. Click OK, Ghidra will warn that you want to restart Ghidra to apply the changes.
3. Restart Ghidra.
4. Open the Script Manager window and click the button on the top right that says Manage Scripts Directories. Add the folder you extracted earlier (the folder should have all the .java files inside of it). Ghidra is now aware of the existence of the script and can be launched from the DWARF folder.

## Usage instructions
1. Launch the script, you will be prompted for an output file. Select a suitable location and click OK.
2. The scripting console will log all interesting events that the script is doing and if everything went accordingly, it should say that it sucessfully exported the file at the end.
3. You may now load your newly created ELF file to GDB after connecting to your target via the `symbol-file` command.

If Ghidra complains about not being able to find a library at step 2, make sure you performed the second step of the installation instructions correctly (it is required for Ghidra to be aware of the existence of the .jar file).

## License
This script is licensed under the MIT license which grants you the rights to share, modify and distribute this script as long as you mention the original author. For more details, please consult the LICENSE file.

