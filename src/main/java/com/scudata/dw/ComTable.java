package com.scudata.dw;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;

import com.scudata.common.MD5;
import com.scudata.common.MessageManager;
import com.scudata.common.RQException;
import com.scudata.dm.Context;
import com.scudata.dm.DataStruct;
import com.scudata.dm.FileGroup;
import com.scudata.dm.FileObject;
import com.scudata.dm.LongArray;
import com.scudata.dm.cursor.ICursor;
import com.scudata.resources.EngineMessage;
import com.scudata.util.FileSyncManager;

/**
 * 组表类
 * 区块链*1：文件头（文件标识、区块大小、空闲位置、列信息、表信息、复列数据结构、分段信息、补块信息（插入记录数、修改记录数、删除记录数）），每次更改重写
 * 区块链*T：表分块信息（每个列块的记录数）
 * 区块链*C：列分块信息（列区块位置、维字段最大最小值）
 * 区块链*C：列数据（按区块压缩）
 * 区块链*T：补块，每次更改重写
 * @author runqian
 *
 */
abstract public class ComTable implements IBlockStorage {
	// 补文件后缀由_SF改为.ext
	public static final String SF_SUFFIX = ".ext"; //补文件后缀
	
	protected File file;
	protected RandomAccessFile raf;
	protected PhyTable baseTable;
	
	protected int blockSize; // 块大小
	protected transient int enlargeSize; // 扩大文件时的增幅
	protected BlockLink headerBlockLink;
	
	protected byte []reserve = new byte[32]; // 保留，字节1存放版本，字节2存放是否压缩，0：压缩，1：不压缩
	protected long freePos = 0; // 空闲位置
	protected long fileSize; // 文件总大小
	
	// 密码已废弃，文件中保留这个属性， 读写时不再做密码检查
	protected String writePswHash; // 写密码哈希值，版本1添加
	protected String readPswHash; // 读密码哈希值，版本1添加
	
	protected String distribute; // 分布表达式，版本2添加

	//private transient boolean canWrite = true;
	//private transient boolean canRead = true;
	
	protected StructManager structManager; // 复列的数据结构
	protected transient Context ctx;
	
	private transient ComTable sfGroupTable;
	private transient Integer partition; // 组文件所属分区
	private transient int cursorCount;//打开的游标的个数
	
	/**
	 * 获得组表的补文件
	 * @param file 组表文件
	 * @return
	 */
	public static File getSupplementFile(File file) {
		String pathName = file.getAbsolutePath();
		pathName += SF_SUFFIX;
		return new File(pathName);
	}

	/**
	 * 复制当前组表的结构产生新组表文件
	 * @param sf
	 * @return
	 */
	public ComTable dupStruct(File sf) {
		checkWritable();
		ComTable newGroupTable = null;
		
		try {
			//生成新组表文件
			if (this instanceof ColComTable) {
				newGroupTable = new ColComTable(sf, (ColComTable)this);
			} else {
				newGroupTable = new RowComTable(sf, (RowComTable)this);
			}
		} catch (Exception e) {
			if (newGroupTable != null) newGroupTable.close();
			sf.delete();
			throw new RQException(e.getMessage(), e);
		}
		
		return newGroupTable;
	}
	
	/**
	 * 得到补文件对象
	 * @param isCreate 是否新建
	 * @return
	 */
	public ComTable getSupplement(boolean isCreate) {
		if (sfGroupTable == null) {
			File sf = getSupplementFile(file);
			if (sf.exists()) {
				try {
					sfGroupTable = open(sf, ctx);
					//sfGroupTable.canWrite = canWrite;
					//sfGroupTable.canRead = canRead;
				} catch (IOException e) {
					throw new RQException(e);
				}
			} else if (isCreate) {
				sfGroupTable = dupStruct(sf);
			}
		}
		
		return sfGroupTable;
	}

	/**
	 * 打开已经存在的组表
	 * @param file
	 * @param ctx
	 * @return
	 * @throws IOException
	 */
	public static ComTable open(File file, Context ctx) throws IOException {
		if (!file.exists()) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("file.fileNotExist", file.getAbsolutePath()));
		}
		
		RandomAccessFile raf = new RandomAccessFile(file, "rw");
		try {
			raf.seek(6);
			if (raf.read() == 'r') {
				return new RowComTable(file, ctx);
			} else {
				return new ColComTable(file, ctx);
			}
		} finally {
			raf.close();
		}
	}

	/**
	 * 打开已经存在的组表,不检查出错日志，仅内部使用
	 * @param file
	 * @return
	 * @throws IOException
	 */
	public static ComTable createGroupTable(File file) throws IOException {
		if (!file.exists()) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("file.fileNotExist", file.getAbsolutePath()));
		}
		
		RandomAccessFile raf = new RandomAccessFile(file, "rw");
		try {
			raf.seek(6);
			if (raf.read() == 'r') {
				return new RowComTable(file);
			} else {
				return new ColComTable(file);
			}
		} finally {
			raf.close();
		}
	}
	
	/**
	 * 打开基表
	 * @param file
	 * @param ctx
	 * @return
	 */
	public static PhyTable openBaseTable(File file, Context ctx) {
		try {
			ComTable groupTable = open(file, ctx);
			return groupTable.getBaseTable();
		} catch (IOException e) {
			throw new RQException(e.getMessage(), e);
		}
	}

	/**
	 * 取基表
	 * @return
	 */
	public PhyTable getBaseTable() {
		return baseTable;
	}

	/**
	 * 设置区块大小
	 * @param size
	 */
	protected void setBlockSize(int size) {
		blockSize = size;
		enlargeSize = size * 16;
	}
	
	protected void finalize() throws Throwable {
		close();
	}
	
	/**
	 * 删除组表文件
	 */
	public void delete() {
		// 用于f.create@y()
		checkWritable();
		ComTable sgt = getSupplement(false);
		if (sgt != null) {
			sgt.delete();
		}
		
		PhyTable table = getBaseTable();
		
		try {
			table.deleteIndex(null);
			table.deleteCuboid(null);
			ArrayList<PhyTable> tables = table.getTableList();
			for (PhyTable td : tables) {
				td.deleteIndex(null);
				td.deleteCuboid(null);
			}

			close();
			file.delete();
		} catch (IOException e) {
			throw new RQException(e);
		}
	}
	
	public void close() {
		try {
			baseTable.appendCache();
			ArrayList <PhyTable> tables = baseTable.getTableList();
			for (PhyTable table : tables) {
				table.appendCache();
			}
			
			raf.close();
			if (sfGroupTable != null) {
				sfGroupTable.close();
			}
		} catch (IOException e) {
			throw new RQException(e);
		}
	}

	protected abstract void readHeader() throws IOException;
	
	protected abstract void writeHeader() throws IOException;
	
	protected void flush() throws IOException {
		raf.getChannel().force(false);
	}
	
	void save() throws IOException {
		writeHeader();
		flush();
	}
	
	public int getBlockSize() {
		return blockSize;
	}
	
	/**
	 * 读取一块数据
	 */
	public synchronized void loadBlock(long pos, byte []block) throws IOException {
		raf.seek(pos);
		raf.readFully(block);
	}

	public void saveBlock(long pos, byte []block) throws IOException {
		raf.seek(pos);
		raf.write(block);
	}
	
	public void saveBlock(long pos, byte []block, int off, int len) throws IOException {
		raf.seek(pos);
		raf.write(block, off, len);
	}
	
	/**
	 * 申请一个新块
	 */
	public long applyNewBlock() throws IOException {
		long pos = freePos;
		if (pos >= fileSize) {
			enlargeFile();
		}
		
		freePos += blockSize;
		return pos;
	}
	
	private void enlargeFile() throws IOException {
		fileSize += enlargeSize;
		raf.setLength(fileSize);
	}

	public StructManager getStructManager() {
		return structManager;
	}
	
	int getDataStructID(DataStruct ds) {
		return structManager.getDataStructID(ds);
	}

	DataStruct getDataStruct(int id) {
		return structManager.getDataStruct(id);
	}
	
	public File getFile() {
		return file;
	}

	/**
	 * 开启事务保护
	 * 把组表的关键信息暂存到临时文件
	 * @param table
	 * @throws IOException
	 */
	protected void beginTransaction(PhyTable table) throws IOException {
		byte []bytes = new byte[blockSize];
		raf.seek(0);
		raf.readFully(bytes);
		byte []mac = MD5.get(bytes);
		
		String dir = file.getAbsolutePath() + "_TransactionLog";
		FileObject logFile = new FileObject(dir);
		logFile.delete();
		RandomAccessFile raf = new RandomAccessFile(logFile.getLocalFile().file(), "rw");
		raf.seek(0);
		raf.write(bytes);
		raf.write(mac);
		raf.getChannel().force(false);
		raf.close();
		
		//记着更新的table name，是为了恢复索引
		if (table != null) {
			if (table.indexNames == null)
				return;
			dir = file.getAbsolutePath() + "_I_TransactionLog";
			logFile = new FileObject(dir);
			logFile.delete();
			raf = new RandomAccessFile(logFile.getLocalFile().file(), "rw");
			raf.seek(0);
			raf.writeUTF(table.tableName);
			raf.getChannel().force(false);
			raf.close();
		}
	}
	
	/**
	 * 提交事务
	 * 删除备份日志文件
	 * @param step 0，删除组表日志；1，删除索引日志
	 */
	protected void commitTransaction(int step) {
		if (step == 1) {
			String dir = file.getAbsolutePath() + "_I_TransactionLog";
			FileObject logFile = new FileObject(dir);
			logFile.delete();
		} else {
			String dir = file.getAbsolutePath() + "_TransactionLog";
			FileObject logFile = new FileObject(dir);
			logFile.delete();
		}
	}
	
	/**
	 * 恢复事务
	 * 当写组表时出现异常情况，使用这个方法来恢复到写事务之前的状态
	 */
	protected void restoreTransaction() {
		String dir = file.getAbsolutePath() + "_TransactionLog";
		FileObject logFile = new FileObject(dir);
		if (logFile.isExists()) {
			//需要调用rollback
			MessageManager mm = EngineMessage.get();
			throw new RQException(file.getName() + mm.getMessage("dw.needRollback"));
		}
		
		dir = file.getAbsolutePath() + "_I_TransactionLog";
		logFile = new FileObject(dir);
		if (logFile.isExists()) {
			//需要调用rollback
			MessageManager mm = EngineMessage.get();
			throw new RQException(file.getName() + mm.getMessage("dw.needRollback"));
		}

	}
	
	/**
	 * 重置组表
	 * @param file 组表文件
	 * @param opt r,重置为行存。c,重置为列存。
	 * @param ctx
	 * @param distribute 新的分布表达式，省略则用原来的
	 * @return
	 */
	public boolean reset(File file, String opt, Context ctx, String distribute) {
		checkWritable();
		if (distribute == null) {
			distribute = this.distribute;
		}
		
		boolean isCol = this instanceof ColComTable;
		boolean hasQ = false;
		boolean hasN = false;
		boolean compress = false; // 压缩
		boolean uncompress = false; // 不压缩
		
		if (opt != null) {
			if (opt.indexOf('r') != -1) {
				isCol = false;
			} else if (opt.indexOf('c') != -1) {
				isCol = true;
			}
			
			if (opt.indexOf('u') != -1) {
				uncompress = true;
			}
			
			if (opt.indexOf('z') != -1) {
				compress = true;
			}
			
			if (compress && uncompress) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(opt + mm.getMessage("engine.optConflict"));
			}
			
			if (opt.indexOf('q') != -1) {
				hasQ = true;
				if (file != null) {
					//有@q时不能有f'
					MessageManager mm = EngineMessage.get();
					throw new RQException("reset" + mm.getMessage("function.invalidParam"));
				}
			}
			
			if (opt.indexOf('n') != -1) {
				hasN = true;
				if (file == null) {
					//有@n时必须有f'
					MessageManager mm = EngineMessage.get();
					throw new RQException("reset" + mm.getMessage("function.invalidParam"));
				}
			}
		}
		
		ComTable sgt = getSupplement(false);
		if (hasQ) {
			if (sgt != null) {
				sgt.reset(file, opt, ctx, distribute);
				sgt.close();
				sgt = null;
			}
		}
		
		String []srcColNames = baseTable.getColNames();
		int len = srcColNames.length;
		String []colNames = new String[len];
		
		if (baseTable instanceof ColPhyTable) {
			for (int i = 0; i < len; i++) {
				ColumnMetaData col = ((ColPhyTable)baseTable).getColumn(srcColNames[i]);
				if (col.isDim()) {
					colNames[i] = "#" + srcColNames[i];
				} else {
					colNames[i] = srcColNames[i];
				}
			}
		} else {
			boolean[] isDim = ((RowPhyTable)baseTable).getDimIndex();
			for (int i = 0; i < len; i++) {
				if (isDim[i]) {
					colNames[i] = "#" + srcColNames[i];
				} else {
					colNames[i] = srcColNames[i];
				}
			}
		}

		File newFile;
		FileObject newFileObj = null;
		if (file == null) {
			newFileObj = new FileObject(this.file.getAbsolutePath());
			newFileObj = new FileObject(newFileObj.createTempFile(this.file.getName()));
			newFile = newFileObj.getLocalFile().file();
		} else {
			newFile = file;
		}
		
		// 生成分段选项，是否按第一字段分段
		String newOpt = null;
		String segmentCol = baseTable.getSegmentCol();
		if (segmentCol != null) {
			newOpt = "p";
		}
		
		ComTable newGroupTable = null;
		try {
			//生成新组表文件
			if (isCol) {
				newGroupTable = new ColComTable(newFile, colNames, distribute, newOpt, ctx);
				if (compress) {
					newGroupTable.setCompress(true);
				} else if (uncompress) {
					newGroupTable.setCompress(false);
				} else {
					newGroupTable.setCompress(isCompress());
				}
			} else {
				newGroupTable = new RowComTable(newFile, colNames, distribute, newOpt, ctx);
			}
			
			//处理分段
			boolean needSeg = baseTable.segmentCol != null;
			if (needSeg) {
				newGroupTable.baseTable.setSegmentCol(baseTable.segmentCol, baseTable.segmentSerialLen);
			}
			PhyTable newBaseTable = newGroupTable.baseTable;
			
			//建立基表的子表
			ArrayList<PhyTable> tableList = baseTable.tableList;
			for (PhyTable t : tableList) {
				srcColNames = t.getColNames();
				len = srcColNames.length;
				colNames = new String[len];
				
				if (t instanceof ColPhyTable) {
					for (int i = 0; i < len; i++) {
						ColumnMetaData col = ((ColPhyTable)t).getColumn(srcColNames[i]);
						if (col.isDim()) {
							colNames[i] = "#" + srcColNames[i];
						} else {
							colNames[i] = srcColNames[i];
						}
					}
				} else {
					for (int i = 0; i < len; i++) {
						boolean[] isDim = ((RowPhyTable)t).getDimIndex();
						if (isDim[i]) {
							colNames[i] = "#" + srcColNames[i];
						} else {
							colNames[i] = srcColNames[i];
						}
					}
				}
				newBaseTable.createAnnexTable(colNames, t.getSerialBytesLen(), t.tableName);
			}
			
			if (hasN) {
				newGroupTable.save();
				newGroupTable.close();
				return Boolean.TRUE;
			}
			
			//写数据到新基表
			ICursor cs = null;
			if (hasQ) {
				//获得自己的纯游标（不含补文件的）
				if (baseTable instanceof ColPhyTable) {
					cs = new Cursor((ColPhyTable)baseTable);
				} else {
					cs = new RowCursor((RowPhyTable)baseTable);
				}
				
			} else {
				cs = baseTable.cursor();
			}
			
			int startBlock = -1;//hasQ时才有用
			if (hasQ) {
				//从基表附表中根据补区找最靠前的块号
				startBlock = baseTable.getFirstBlockFromModifyRecord();
				tableList = baseTable.tableList;
				for (PhyTable t : tableList) {
					int blk = t.getFirstBlockFromModifyRecord();
					if (startBlock == -1 ) {
						startBlock = blk;
					} else if (blk != -1 && startBlock > blk) {
						startBlock = blk;
					}
				}
				if (startBlock < 0) {
					newGroupTable.delete();
					return Boolean.FALSE;
				} else if (startBlock == 0) {
					hasQ = false;//如果reset点就在第一块，则完全reset
				} else {
					((Cursor) cs).setSegment(startBlock, baseTable.getDataBlockCount());
				}
			}
			newBaseTable.append(cs);
			newBaseTable.appendCache();
			
			//写数据到基表的子表
			for (PhyTable t : tableList) {
				PhyTable newTable = newBaseTable.getAnnexTable(t.tableName);
				if (hasQ) {
					//获得自己的纯游标（不含补文件的）
					if (t instanceof ColPhyTable) {
						cs = new Cursor((ColPhyTable)t, t.allColNames);
					} else {
						cs = new RowCursor((RowPhyTable)t, t.allColNames);
					}
				} else {
					cs = t.cursor(t.allColNames);
				}
				if (hasQ) {
					((JoinTableCursor) cs).setSegment(startBlock, t.getDataBlockCount());
				}
				newTable.append(cs);
				newTable.appendCache();
			}

			if (file != null) {
				newGroupTable.close();
				return Boolean.TRUE;
			}
			
			if (hasQ) {
				//reset原组表，截止到块号
				long pos, freePos;
				freePos = baseTable.resetByBlock(startBlock);
				for (PhyTable t : tableList) {
					pos = t.resetByBlock(startBlock);
					if (freePos < pos) {
						freePos = pos;
					}
				}
				this.freePos = freePos;
				save();
				readHeader();
				tableList = baseTable.tableList;
				
				//写入块号之后的数据
				cs = newBaseTable.cursor();
				baseTable.append(cs);
				ArrayList<PhyTable> newTableList = newBaseTable.tableList;
				for (int i = 0; i < tableList.size(); i++) {
					PhyTable t = newTableList.get(i);
					cs = t.cursor(t.allColNames);
					tableList.get(i).append(cs);
				}
				
				//删除临时组表
				newGroupTable.close();
				newGroupTable.file.delete();
				
				//重建索引文件
				baseTable.resetIndex(ctx);
				newTableList = baseTable.tableList;
				for (PhyTable table : newTableList) {
					table.resetIndex(ctx);
				}
				
				//hasQ时不用处理cuboid
				return Boolean.TRUE;
			} else {
				if (sgt != null) {
					sgt.delete();
				}
			}
			
			//如果没有f'参数就建立索引
			//基表索引
			String []indexNames = baseTable.indexNames;
			if (indexNames != null) {
				String [][]indexFields = baseTable.indexFields;
				String [][]indexValueFields = baseTable.indexValueFields;
				for (int j = 0, size = indexNames.length; j < size; j++) {
					newBaseTable.addIndex(indexNames[j], indexFields[j], indexValueFields[j]);
				}
			}
			//子表索引
			ArrayList<PhyTable> newTableList = newBaseTable.tableList;
			len = tableList.size();
			for (int i = 0; i < len; i++) {
				PhyTable oldTable = tableList.get(i);
				PhyTable newTable = newTableList.get(i);
				indexNames = oldTable.indexNames;
				if (indexNames == null) continue;
				String [][]indexFields = oldTable.indexFields;
				String [][]indexValueFields = oldTable.indexValueFields;
				for (int j = 0, size = indexNames.length; j < size; j++) {
					newTable.addIndex(indexNames[j], indexFields[j], indexValueFields[j]);
				}
			}
			
			//基表cuboid
			String []cuboids = baseTable.cuboids;
			if (cuboids != null) {
				for (String cuboid : cuboids) {
					newBaseTable.addCuboid(cuboid);
				}
			}
			//子表cuboid
			for (int i = 0; i < len; i++) {
				PhyTable oldTable = tableList.get(i);
				PhyTable newTable = newTableList.get(i);
				cuboids = oldTable.cuboids;
				if (cuboids == null) continue;
				for (String addCuboid : cuboids) {
					newTable.addCuboid(addCuboid);
				}
			}
			
		} catch (Exception e) {
			if (newGroupTable != null) newGroupTable.close();
			newFile.delete();
			throw new RQException(e.getMessage(), e);
		}
		
		//删除旧的组表
		String path = this.file.getAbsolutePath();
		close();
		boolean b = this.file.delete();
		if (!b) 
			return Boolean.FALSE;
		newGroupTable.close();
		newFileObj.move(path, null);

		try{
			newGroupTable = open(this.file, ctx);
		} catch (IOException e) {
			throw new RQException(e.getMessage(), e);
		}
		
		//重建索引文件和cuboid
		newGroupTable.baseTable.resetIndex(ctx);
		newGroupTable.baseTable.resetCuboid(ctx);
		ArrayList<PhyTable> newTableList = newGroupTable.baseTable.tableList;
		for (PhyTable table : newTableList) {
			table.resetIndex(ctx);
			table.resetCuboid(ctx);
		}
		newGroupTable.close();
		
		return Boolean.TRUE;
	}
	
	/**
	 * 把当前组表写出到一个新的文件组
	 * @param fileGroup 文件组
	 * @param opt r,重置为行存。c,重置为列存。
	 * @param ctx
	 * @param distribute 新的分布表达式，省略则用原来的
	 * @return
	 */
	public boolean resetFileGroup(FileGroup fileGroup, String opt, Context ctx, String distribute) {
		if (distribute == null) {
			distribute = this.distribute;
		}
		boolean isCol = this instanceof ColComTable;
		boolean uncompress = false; // 不压缩
		if (opt != null) {
			if (opt.indexOf('r') != -1) {
				isCol = false;
			} else if (opt.indexOf('c') != -1) {
				isCol = true;
			}
			
			if (opt.indexOf('u') != -1) {
				uncompress = true;
			}
			
			if (opt.indexOf('z') != -1) {
				uncompress = false;
			}
		}
		
		String []srcColNames = baseTable.getColNames();
		int len = srcColNames.length;
		String []colNames = new String[len];
		
		if (baseTable instanceof ColPhyTable) {
			for (int i = 0; i < len; i++) {
				ColumnMetaData col = ((ColPhyTable)baseTable).getColumn(srcColNames[i]);
				if (col.isDim()) {
					colNames[i] = "#" + srcColNames[i];
				} else {
					colNames[i] = srcColNames[i];
				}
			}
		} else {
			boolean[] isDim = ((RowPhyTable)baseTable).getDimIndex();
			for (int i = 0; i < len; i++) {
				if (isDim[i]) {
					colNames[i] = "#" + srcColNames[i];
				} else {
					colNames[i] = srcColNames[i];
				}
			}
		}
		
		// 生成分段选项，是否按第一字段分段
		String newOpt = "y";
		String segmentCol = baseTable.getSegmentCol();
		if (segmentCol != null) {
			newOpt = "p";
		}
		
		if (isCol) {
			newOpt += 'c';
		} else {
			newOpt += 'r';
		}
		
		if (uncompress) {
			newOpt += 'u';
		}
		try {
			//写基表
			PhyTableGroup newTableGroup = fileGroup.create(colNames, distribute, newOpt, ctx);
			ICursor cs = baseTable.cursor();
			newTableGroup.append(cs, "xi");
			
			//写子表
			ArrayList<PhyTable> tableList = baseTable.tableList;
			for (PhyTable t : tableList) {
				len = t.colNames.length;
				colNames = Arrays.copyOf(t.colNames, len);
				if (t instanceof ColPhyTable) {
					for (int i = 0; i < len; i++) {
						ColumnMetaData col = ((ColPhyTable)t).getColumn(colNames[i]);
						if (col.isDim()) {
							colNames[i] = "#" + colNames[i];
						}
					}
				} else {
					boolean[] isDim = ((RowPhyTable)t).getDimIndex();
					for (int i = 0; i < len; i++) {
						if (isDim[i]) {
							colNames[i] = "#" + colNames[i];
						}
					}
				}
				IPhyTable newTable = newTableGroup.createAnnexTable(colNames, t.getSerialBytesLen(), t.tableName);
				
				//附表的游标，取出字段里要包含基表所有字段，这是因为需要计算分布
				String[] allColNames = Arrays.copyOf(srcColNames, srcColNames.length + t.colNames.length);
				System.arraycopy(t.colNames, 0, allColNames, srcColNames.length, t.colNames.length);
				cs = t.cursor(allColNames);
				newTable.append(cs, "xi");
			}

			newTableGroup.close();
			return Boolean.TRUE;
		} catch (IOException e) {
			throw new RQException(e.getMessage(), e);
		}
	}
	
	public abstract long[] getBlockLinkInfo();
	
	/**
	 * 对比块链信息
	 * @param blockLinkInfo 新组表文件的区块信息
	 * @return 每个需要同步的区块链的起始地址
	 */
	public long[] cmpBlockLinkInfo(long []blockLinkInfo) {
		//调用这个方法的对象是旧文件
		long []localBlockInfo = getBlockLinkInfo();
		int localSize = localBlockInfo.length / 4;
		int size = blockLinkInfo.length / 4;
		LongArray posArray = new LongArray(1024);
		
		for (int i = 0; i < size; i++) {
			long firstBlockPos = blockLinkInfo[i * 4];
			long lastBlockPos = blockLinkInfo[i * 4 + 1];
			int freeIndex = (int) blockLinkInfo[i * 4 + 2];
			int blockCount = (int) blockLinkInfo[i * 4 + 3];
			
			if (firstBlockPos >= fileSize) {
				//如果大于file size则不处理
				continue;
			}
			boolean find = false;
			for (int j = 0; j < localSize; j++) {
				long localFirstBlockPos = localBlockInfo[j * 4];
				long localLastBlockPos = localBlockInfo[j * 4 + 1];
				int localFreeIndex = (int) localBlockInfo[j * 4 + 2];
				int localBlockCount = (int) localBlockInfo[j * 4 + 3];
				if (firstBlockPos == localFirstBlockPos) {
					find = true;
					if (lastBlockPos < localLastBlockPos) {
						//异常
					}
					if ((lastBlockPos != localLastBlockPos) ||
							(freeIndex != localFreeIndex) || 
							(blockCount !=localBlockCount)) {
						//找到了但是不相等，也需要同步
						posArray.add(localLastBlockPos);
					}
					break;
				}
				
			}
			if (!find) {
				//没找到，需要同步
				posArray.add(firstBlockPos);
			}
		}
		if (posArray.size() == 0) {
			return null;
		}
		return posArray.toArray();
	}
	
	/**
	 * 得到同步地址
	 * @param positions 每个需要同步的区块链的起始地址
	 * @return 需要同步的所有地址
	 */
	public long[] getSyncPosition(long []positions) {
		//补区blockLink 都要同步
		//headerBlockLink 会放到最后一步同步
		//文件尾增量部分会特别处理
		LongArray posArray = new LongArray(1024);
		byte []block = new byte[5];
		for (int i = 0, len = positions.length; i < len; ++i) {
			long pos = positions[i];
			if (pos > 1) {
				try {
					while (pos > 1) {
						posArray.add(pos);
						raf.seek(pos + blockSize - POS_SIZE);
						raf.read(block);
						pos = (((long)(block[0] & 0xff) << 32) +
								((long)(block[1] & 0xff) << 24) +
								((block[2] & 0xff) << 16) +
								((block[3] & 0xff) <<  8) +
								(block[4] & 0xff));
					}
					
				} catch (IOException e) {
					throw new RQException(e.getMessage(), e);
				}
			}
		}
		if (posArray.size() == 0) {
			return null;
		}
		return posArray.toArray();
	}

	/**
	 * 
	 * @return 补区需要同步的所有地址
	 */
	public long[] getModifyPosition() {
		int count = 1 + baseTable.tableList.size();
		
		long []positions = new long[count * 2];
		int c = 0;
		
		positions[c++] = baseTable.modifyBlockLink1.firstBlockPos;
		positions[c++] = baseTable.modifyBlockLink2.firstBlockPos;
		
		for (PhyTable table : baseTable.tableList) {
			positions[c++] = table.modifyBlockLink1.firstBlockPos;
			positions[c++] = table.modifyBlockLink2.firstBlockPos;
		}
		
		LongArray posArray = new LongArray(1024);
		byte []block = new byte[5];
		for (int i = 0, len = positions.length; i < len; ++i) {
			long pos = positions[i];
			if (pos > 1) {
				try {
					while (pos > 1) {
						posArray.add(pos);
						raf.seek(pos + blockSize - POS_SIZE);
						raf.read(block);
						pos = (((long)(block[0] & 0xff) << 32) +
								((long)(block[1] & 0xff) << 24) +
								((block[2] & 0xff) << 16) +
								((block[3] & 0xff) <<  8) +
								(block[4] & 0xff));
					}
					
				} catch (IOException e) {
					throw new RQException(e.getMessage(), e);
				}
			}
		}
		if (posArray.size() == 0) {
			return null;
		}
		return posArray.toArray();
	}
	
	/**
	 * 返回块链上的所有头地址
	 * @return
	 */
	public long[] getHeaderPosition() {
		//headerBlockLink 会放到最后一步同步
		LongArray posArray = new LongArray(1024);
		byte []block = new byte[5];
		long pos = 0;
		try {
			do {
				posArray.add(pos);
				raf.seek(pos + blockSize - POS_SIZE);
				raf.read(block);
				pos = (((long)(block[0] & 0xff) << 32) +
						((long)(block[1] & 0xff) << 24) +
						((block[2] & 0xff) << 16) +
						((block[3] & 0xff) <<  8) +
						(block[4] & 0xff));
			} while (pos > 1);
			
		} catch (IOException e) {
			throw new RQException(e.getMessage(), e);
		}
		if (posArray.size() == 0) {
			return null;
		}
		return posArray.toArray();
	}
	
	void setPassword(String writePsw, String readPsw) {
		if (writePsw != null) {
			MD5 md5 = new MD5();
			this.writePswHash = md5.getMD5ofStr(writePsw);
		}
		
		if (readPsw != null) {
			MD5 md5 = new MD5();
			this.readPswHash = md5.getMD5ofStr(readPsw);
		}
	}
	
	/**
	 * 返回是否设置了密码
	 * @return true：有密码，false：没密码
	 */
	public boolean hasPassword() {
		return writePswHash != null || readPswHash != null;
	}
	
	/**
	 * 设置了密码的组表需要调用此函数才能访问
	 * @param psw 写密码或者读密码，如果是写密码则既可读又可写，如果是读密码则只可读
	 */
	public void checkPassword(String psw) {
		/*if (writePswHash != null) {
			if (psw == null) {
				canWrite = false;
			} else {
				MD5 md5 = new MD5();
				canWrite = md5.getMD5ofStr(psw).equals(writePswHash);
				if (canWrite) {
					canRead = true;
					return;
				}
			}
		}
		
		if (readPswHash != null) {
			if (psw == null) {
				canRead = false;
			} else {
				MD5 md5 = new MD5();
				canRead = md5.getMD5ofStr(psw).equals(readPswHash);
				if (!canRead) {
					//added by hhw 2019.6如果读是false写也就为false
					canWrite = false;
					MessageManager mm = EngineMessage.get();
					throw new RQException(mm.getMessage("cellset.pswError"));
				}
			}
		}*/
	}
	
	public void checkWritable() {
		/*if (!canWrite) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("dw.needWritePassword"));
		}*/
	}
	
	public void checkReadable() {
		/*if (!canRead) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("dw.needReadPassword"));
		}*/
	}
	
	/**
	 * 返回是否可写
	 * @return
	 */
	public boolean canWrite() {
		return true;
	}
	
	/**
	 * 返回是否可读
	 * @return
	 */
	public boolean canRead() {
		return true;
	}
	
	Object getSyncObject() {
		return FileSyncManager.getSyncObject(file);
	}
	
	// 取分布表达式串
	public String getDistribute() {
		return distribute;
	}
	
	// 返回是否压缩列数据
	public boolean isCompress() {
		return reserve[1] == 0;
	}
	
	// 设置是否压缩列数据
	public void setCompress(boolean isCompress) {
		if (isCompress) {
			reserve[1] = 0;
		} else {
			reserve[1] = 1;
		}
	}
	
	public void setPartition(Integer partition) {
		this.partition = partition;
	}
	
	public Integer getPartition() {
		return partition;
	}
	
	public RandomAccessFile getRaf() {
		return raf;
	}
	
	public void openCursorEvent() {
		Object syncObj = getSyncObject();
		synchronized(syncObj) {
			cursorCount ++;
		}
	}
	
	public void closeCursorEvent() {
		Object syncObj = getSyncObject();
		synchronized(syncObj) {
			cursorCount --;
			if (cursorCount <= 0) {
				close();
			}
		}
	}
}