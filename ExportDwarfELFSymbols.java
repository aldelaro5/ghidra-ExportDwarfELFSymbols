//Export an ELF file containing only DWARF symbols from a Ghidra program
//
//This allows to have symbols for a target with non standard formats
//such as console ROMs or console binaries. Ideal to use with GDB
//for debugging with symbols on esotheric targets such as emulator's
//GDB stubs.
//@author aldelaro5
//@category DWARF

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.lang.Endian;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import docking.widgets.filechooser.GhidraFileChooser;
import docking.widgets.filechooser.GhidraFileChooserMode;

public class ExportDwarfELFSymbols extends GhidraScript
{
    private class SectionInfo
    {
	public String name;
	public byte[] content;
    }
    
    class SectionsCallback implements LibdwarfLibrary.Dwarf_Callback_Func
    {
	public ArrayList<String> sections = new ArrayList<>();

	@Override
	public int apply(Pointer charPtr1, int int1, long Dwarf_Unsigned1, long Dwarf_Unsigned2, long Dwarf_Unsigned3,
		long Dwarf_Unsigned4, LongByReference Dwarf_UnsignedPtr1, Pointer voidPtr1, IntByReference intPtr1)
	{
	    String name = new String(charPtr1.getString(0));
	    println("Generated Dwarf section " + name);
	    sections.add(name);
	    return sections.size() - 1;
	}
    }

    private static final byte[] elfMagic = { 0x7F, 0x45, 0x4C, 0x46 };

    private static final byte elfClass32Bit = 1;
    private static final byte elfClass64Bit = 1;

    private static final byte elfDataLittleEndian = 1;
    private static final byte elfDataBigEndian = 2;

    private static final byte elfVersion = 1;
    private static final short elfTypeDyn = 3;

    private static final int elfSectionTypeProgBits = 1;
    private static final int elfSectionTypeStrTab = 3;

    private static final int elfHeaderSize32Bit = 0x34;
    private static final int elfSectionHeaderEntrySize32Bit = 0x28;
    private static final int elfHeaderSize64Bit = 0x40;
    private static final int elfSectionHeaderEntrySize64Bit = 0x40;

    PointerByReference dbgRef = new PointerByReference();
    PointerByReference errRef = new PointerByReference();
    LibdwarfLibrary.Dwarf_P_Debug dbg;
    SectionsCallback sectionsCallback = new SectionsCallback();
    
    File outputFile;

    @Override
    protected void run() throws Exception
    {	
	GhidraFileChooser fileChooser = new GhidraFileChooser(null);
        fileChooser.setTitle("Select an output file");
        fileChooser.setFileSelectionMode(GhidraFileChooserMode.FILES_ONLY);
        outputFile = fileChooser.getSelectedFile(true);
        if (outputFile == null) 
        {
            println("No output file was provided, exiting...");
            return;
        }
	
        println("Initialising...");
        int pointerSize = currentProgram.getDefaultPointerSize();
        String processorStr = currentProgram.getLanguage().getProcessor().toString();
	boolean is64Bit = pointerSize > 4;
	Endian endianness = currentProgram.getLanguage().getLanguageDescription().getEndian();
	println("Pointer size: " + pointerSize);
	println("Endianness: " + endianness.toString());
	println("Processor: " + processorStr);	
	int bitNessFlags = LibdwarfLibrary.DW_DLC_POINTER32 | LibdwarfLibrary.DW_DLC_OFFSET32;
	if (is64Bit)
	    bitNessFlags = LibdwarfLibrary.DW_DLC_POINTER64 | LibdwarfLibrary.DW_DLC_OFFSET64;
	int endiannessFlag = LibdwarfLibrary.DW_DLC_TARGET_LITTLEENDIAN;
	if (endianness == Endian.BIG)
	    endiannessFlag = LibdwarfLibrary.DW_DLC_TARGET_BIGENDIAN;
	
	int err = LibdwarfLibrary.INSTANCE.dwarf_producer_init(
		  LibdwarfLibrary.DW_DLC_WRITE | LibdwarfLibrary.DW_DLC_SYMBOLIC_RELOCATIONS | 
		  bitNessFlags | endiannessFlag, sectionsCallback, null, null, null, 
		  getProgramLibdwarfAbiName(currentProgram), "V5", null, dbgRef,
		  errRef);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	{
	    printLibdwarfError("Error with dwarf_producer_init");
	    return;
	}
	dbg = new LibdwarfLibrary.Dwarf_P_Debug(dbgRef.getValue());
	ArrayList<SectionInfo> sections;
	try
	{
	    println("Done, adding debug info...");
	    addDebugInfo(currentProgram);
	    println("Done, generating the Dwarf sections...");
	    sections = generateDwarfSections();
	}
	catch (Exception ex)
	{
	    printLibdwarfError(ex.getMessage());
	    return;
	}

	println("Done, finalising...");
	err = LibdwarfLibrary.INSTANCE.dwarf_producer_finish_a(dbg, errRef);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	{
	    printLibdwarfError("Error with dwarf_producer_finish_a");
	    return;
	}
	println("Done, generating the ELF file...");
	makeElf(sections, currentProgram);
	println("Sucessfully written the ELF file to " + outputFile.getPath());
    }

    String getProgramLibdwarfAbiName(Program prog)
    {
	int pointerSize = prog.getDefaultPointerSize();
	switch (prog.getLanguage().getProcessor().toString())
	{
	case "ARM":
	    return "arm";
	case "AArch64":
	    return "arm64";
	case "PowerPC":
	    return pointerSize <= 4 ? "ppc" : "ppc64";
	case "MIPS":
	    return "mips";
	// default to x86
	case "x86":
	default:
	    return pointerSize <= 4 ? "x86" : "x86_64";
	}
    }

    private void printLibdwarfError(String msg)
    {
	var dErr = new LibdwarfLibrary.Dwarf_Error(errRef.getValue());
	long errno = LibdwarfLibrary.INSTANCE.dwarf_errno(dErr);
	String errStr = LibdwarfLibrary.INSTANCE.dwarf_errmsg_by_number(errno);
	println(msg + ": " + errStr);
    }

    void addDebugInfo(Program prog) throws Exception
    {
	int err = LibdwarfLibrary.INSTANCE.dwarf_pro_set_default_string_form(dbg, LibdwarfLibrary.DW_FORM_string, errRef);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	    throw new Exception("Error with dwarf_pro_set_default_string_form");
	
	PointerByReference cuRef = new PointerByReference();
	err = LibdwarfLibrary.INSTANCE.dwarf_new_die_a(dbg, LibdwarfLibrary.DW_TAG_compile_unit, null, null, null, null, cuRef, errRef);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	    throw new Exception("Error with dwarf_new_die_a cu");
	
	var cu = new LibdwarfLibrary.Dwarf_P_Die(cuRef.getValue());

	println("addDebugInfo -> Adding functions...");
	FunctionIterator funcs = getFunctions(prog);
	for (Function f : funcs)
	    addFunction(cu, f);
	println("addDebugInfo -> Done adding functions");

	err = LibdwarfLibrary.INSTANCE.dwarf_add_die_to_debug_a(dbg, cu, null);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	    throw new Exception("Error with dwarf_add_die_to_debug_a");
    }

    FunctionIterator getFunctions(Program prog)
    {
	FunctionManager fm = prog.getFunctionManager();
	return fm.getFunctions(true);
    }

    void addFunction(LibdwarfLibrary.Dwarf_P_Die cu, Function func) throws Exception
    {
	String fname = func.getName();
	PointerByReference dieRef = new PointerByReference();
	int err = LibdwarfLibrary.INSTANCE.dwarf_new_die_a(dbg, LibdwarfLibrary.DW_TAG_subprogram, cu, null, null, null, dieRef, errRef);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	    throw new Exception("Error with dwarf_new_die_a function " + fname);
	
	var die = new LibdwarfLibrary.Dwarf_P_Die(dieRef.getValue());
	
	PointerByReference exprRef = new PointerByReference();
	err = LibdwarfLibrary.INSTANCE.dwarf_new_expr_a(dbg, exprRef, errRef);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	    throw new Exception("Error with dwarf_new_expr_a function " + fname);

	var expr = new LibdwarfLibrary.Dwarf_P_Expr(exprRef.getValue());
	PointerByReference attrRef = new PointerByReference();
	LongByReference offsetRef = new LongByReference();
	err = LibdwarfLibrary.INSTANCE.dwarf_add_expr_gen_a(expr, (byte) LibdwarfLibrary.DW_OP_call_frame_cfa, 0, 0, offsetRef, errRef);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	    throw new Exception("Error with dwarf_add_expr_gen_a function " + fname);
	err = LibdwarfLibrary.INSTANCE.dwarf_add_AT_location_expr_a(dbg, die, (short) LibdwarfLibrary.DW_AT_frame_base, expr, attrRef, errRef);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	    throw new Exception("Error with dwarf_add_AT_location_expr_a function " + fname);

	err = LibdwarfLibrary.INSTANCE.dwarf_add_AT_name_a(die, fname, attrRef, errRef);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	    throw new Exception("Error with dwarf_add_AT_name_a function " + fname);
	err = LibdwarfLibrary.INSTANCE.dwarf_add_AT_string_a(dbg, die, (short) LibdwarfLibrary.DW_AT_linkage_name, fname, attrRef, errRef);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	    throw new Exception("Error with dwarf_add_AT_string_a function " + fname);

	long fstart = func.getEntryPoint().getOffset();
	long fend = func.getBody().getMaxAddress().getOffset();

	err = LibdwarfLibrary.INSTANCE.dwarf_add_AT_targ_address_c(dbg, die, (short) LibdwarfLibrary.DW_AT_low_pc, fstart, 0, attrRef, errRef);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	    throw new Exception("Error with dwarf_add_AT_targ_address_c low function " + fname);
	err = LibdwarfLibrary.INSTANCE.dwarf_add_AT_targ_address_c(dbg, die, (short) LibdwarfLibrary.DW_AT_high_pc, fend - 1, 0, attrRef, errRef);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	    throw new Exception("Error with dwarf_add_AT_targ_address_c high function " + fname);
    }

    ArrayList<SectionInfo> generateDwarfSections() throws Exception
    {
	LongByReference sectionCountRef = new LongByReference();
	int err = LibdwarfLibrary.INSTANCE.dwarf_transform_to_disk_form_a(dbg, sectionCountRef, errRef);
	if (err != LibdwarfLibrary.DW_DLV_OK)
	    throw new Exception("Error with dwarf_transform_to_disk_form_a");
	
	long sectionCount = sectionCountRef.getValue();

	HashMap<String, byte[]> sections = new HashMap<String, byte[]>();
	for (long i = 0; i < sectionCount; i++)
	{
	    LongByReference section_index = new LongByReference();
	    LongByReference length = new LongByReference();
	    PointerByReference contentRef = new PointerByReference();
	    err = LibdwarfLibrary.INSTANCE.dwarf_get_section_bytes_a(dbg, i, section_index, length, contentRef, errRef);
	    if (err != LibdwarfLibrary.DW_DLV_OK)
		throw new Exception("Error with dwarf_get_section_bytes_a section " + Long.toString(i));
	    
	    byte[] content = contentRef.getValue().getByteArray(0, (int) length.getValue());
	    String section_name = sectionsCallback.sections.get((int) section_index.getValue());
	    if (!sections.containsKey(section_name))
	    {
		sections.put(section_name, content);
	    } 
	    else
	    {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try
		{
		    baos.write(sections.get(section_name));
		    baos.write(content);
		} 
		catch (IOException e)
		{
		    e.printStackTrace();
		}
		sections.put(section_name, baos.toByteArray());
	    }
	}

	ArrayList<SectionInfo> listSections = new ArrayList<SectionInfo>();
	for (String sectionName : sections.keySet())
	{
	    listSections.add(new SectionInfo() 
	    {{
		name = sectionName;
		content = sections.get(sectionName);
	    }});
	}

	return listSections;
    }

    void makeElf(ArrayList<SectionInfo> sections, Program prog)
    {
	try
	{
	    FileOutputStream fout = new FileOutputStream(outputFile);

	    prepandNullAndShstrtabSections(sections);
	    writeElfHeader(fout, sections, prog);
	    writeElfSectionHeader(fout, sections, prog);

	    // section table
	    for (SectionInfo section : sections)
	    {
		if (section.content != null)
		    fout.write(section.content);
	    }
	    
	    fout.close();
	} 
	catch (Exception e)
	{
	    e.printStackTrace();
	}
    }

    private void prepandNullAndShstrtabSections(ArrayList<SectionInfo> sections)
    {
	// Build the .shstrtab section (including the NULL section empty name)
	ByteArrayOutputStream baos = new ByteArrayOutputStream();
	baos.write(0);
	baos.writeBytes((".shstrtab").getBytes());
	baos.write(0);
	for (SectionInfo section : sections)
	{
	    baos.writeBytes(section.name.getBytes());
	    baos.write(0);
	}

	// Add the NULL and .shstrtab sections
	// The NULL section is needed due to gdb assuming its presence
	sections.add(0, new SectionInfo() 
	{{
	    name = ".shstrtab";
	    content = baos.toByteArray();
	}});
	sections.add(0, new SectionInfo() 
	{{
	    name = "";
	    content = null;
	}});
    }

    private void writeElfHeader(FileOutputStream fout, ArrayList<SectionInfo> sections, Program prog) throws IOException
    {
	boolean is64Bit = prog.getDefaultPointerSize() > 4;
	Endian endianness = prog.getLanguage().getLanguageDescription().getEndian();
	ByteOrder byteOrder = endianness == Endian.LITTLE ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

	int headerSize = is64Bit ? elfHeaderSize64Bit : elfHeaderSize32Bit;
	int sectionHeaderEntrySize = is64Bit ? elfSectionHeaderEntrySize64Bit : elfSectionHeaderEntrySize32Bit;

	// magic
	fout.write(elfMagic);
	// class
	fout.write(is64Bit ? elfClass64Bit : elfClass32Bit);
	// data
	fout.write(endianness == Endian.LITTLE ? elfDataLittleEndian : elfDataBigEndian);
	// version
	fout.write(elfVersion);
	// os abi, unimportant
	fout.write(0);
	// os abi version, unimportant
	fout.write(0);
	// padding
	fout.write(new byte[7]);
	// type, always DYN
	writeInt16ToFout(fout, byteOrder, elfTypeDyn);
	// machine
	writeInt16ToFout(fout, byteOrder, getElfMachine(prog));
	// version
	writeInt32ToFout(fout, byteOrder, elfVersion);
	// entry
	writeIntWithBitness(fout, byteOrder, findEntryPointAddress(prog), is64Bit);
	// program header table start (not used here)
	writeIntWithBitness(fout, byteOrder, 0, is64Bit);
	// section header table start
	writeIntWithBitness(fout, byteOrder, headerSize, is64Bit);
	// flags
	writeInt32ToFout(fout, byteOrder, 0);
	// this header size
	writeInt16ToFout(fout, byteOrder, (short) headerSize);
	// program header table entry size (not used here)
	writeInt16ToFout(fout, byteOrder, (short) 0);
	// program header table number of entry (not used here)
	writeInt16ToFout(fout, byteOrder, (short) 0);
	// section header table entry size
	writeInt16ToFout(fout, byteOrder, (short) sectionHeaderEntrySize);
	// section header table number of entry
	writeInt16ToFout(fout, byteOrder, (short) sections.size());
	// section names index, always will be the second one here
	writeInt16ToFout(fout, byteOrder, (short) 1);
    }

    short getElfMachine(Program prog)
    {
	int pointerSize = prog.getDefaultPointerSize();
	switch (prog.getLanguage().getProcessor().toString())
	{
	case "ARM":
	    return 0x28;
	case "AArch64":
	    return 0xB7;
	case "PowerPC":
	    return (short) (pointerSize <= 4 ? 0x14 : 0x15);
	case "MIPS":
	    return 0x08;
	// default to x86
	case "x86":
	default:
	    return (short) (pointerSize <= 4 ? 0x03 : 0x3E);
	}
    }
    
    long findEntryPointAddress(Program prog)
    {
	// We assume the first entry point is the right one
	var entryPoints = currentProgram.getSymbolTable().getExternalEntryPointIterator();
	if (entryPoints.hasNext())
	    return entryPoints.next().getOffset();
	return 0;
    }

    private void writeElfSectionHeader(FileOutputStream fout, ArrayList<SectionInfo> sections, Program prog) throws IOException
    {
	boolean is64Bit = prog.getDefaultPointerSize() > 4;
	Endian endianness = prog.getLanguage().getLanguageDescription().getEndian();
	ByteOrder byteOrder = endianness == Endian.LITTLE ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
	int headerSize = is64Bit ? elfHeaderSize64Bit : elfHeaderSize32Bit;
	int sectionHeaderEntrySize = is64Bit ? elfSectionHeaderEntrySize64Bit : elfSectionHeaderEntrySize32Bit;

	// SECTION TABLE HEADER
	int accumulatedSectionOffset = (sectionHeaderEntrySize * (sections.size())) + headerSize;
	int accumulatedNameOffset = 0;
	for (SectionInfo section : sections)
	{
	    // The null section must be all blanks
	    if (section.name.isEmpty())
	    {
		fout.write(new byte[sectionHeaderEntrySize]);
		accumulatedNameOffset++;
		continue;
	    }

	    // name offset
	    writeInt32ToFout(fout, byteOrder, accumulatedNameOffset);
	    accumulatedNameOffset += section.name.length() + 1;
	    // type
	    if (section.name == ".shstrtab")
		writeInt32ToFout(fout, byteOrder, elfSectionTypeStrTab);
	    else
		writeInt32ToFout(fout, byteOrder, elfSectionTypeProgBits);
	    // flags
	    writeIntWithBitness(fout, byteOrder, 0, is64Bit);
	    // virtual address
	    writeIntWithBitness(fout, byteOrder, 0, is64Bit);
	    // section offset
	    writeIntWithBitness(fout, byteOrder, accumulatedSectionOffset, is64Bit);
	    accumulatedSectionOffset += section.content.length;
	    // section size
	    writeIntWithBitness(fout, byteOrder, section.content.length, is64Bit);
	    // linked section index
	    writeInt32ToFout(fout, byteOrder, 0);
	    // section info
	    writeInt32ToFout(fout, byteOrder, 0);
	    // alignement, no requirements in particular so just 1
	    writeIntWithBitness(fout, byteOrder, 1, is64Bit);
	    // entry size
	    writeIntWithBitness(fout, byteOrder, 0, is64Bit);
	}
    }

    void writeIntWithBitness(FileOutputStream fout, ByteOrder endianness, long number, boolean is64Bit) throws IOException
    {
	if (is64Bit)
	    writeInt64ToFout(fout, endianness, number);
	else
	    writeInt32ToFout(fout, endianness, (int) number);
    }

    void writeInt64ToFout(FileOutputStream fout, ByteOrder endianness, long number) throws IOException
    {
	fout.write(ByteBuffer.allocate(8).order(endianness).putLong(number).array());
    }

    void writeInt32ToFout(FileOutputStream fout, ByteOrder endianness, int number) throws IOException
    {
	fout.write(ByteBuffer.allocate(4).order(endianness).putInt(number).array());
    }

    void writeInt16ToFout(FileOutputStream fout, ByteOrder endianness, short number) throws IOException
    {
	fout.write(ByteBuffer.allocate(2).order(endianness).putShort(number).array());
    }
}
