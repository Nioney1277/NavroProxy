package ac.navro.anticheat.collision.util;

import ac.navro.anticheat.util.math.Box;

public class CuboidBlockIterator {
	private final int startX, startY, startZ;
	private final int sizeX, sizeY, sizeZ;
	private final int totalSize;
	private int blocksIterated;
	private int x, y, z;

	public CuboidBlockIterator(int startX, int startY, int startZ, int endX, int endY, int endZ) {
		this.startX = startX;
		this.startY = startY;
		this.startZ = startZ;
		this.sizeX = endX - startX + 1;
		this.sizeY = endY - startY + 1;
		this.sizeZ = endZ - startZ + 1;
		this.totalSize = this.sizeX * this.sizeY * this.sizeZ;
	}

	public boolean step() {
		if (this.blocksIterated == this.totalSize) {
			return false;
		} else {
			this.x = this.blocksIterated % this.sizeX;
			int i = this.blocksIterated / this.sizeX;
			this.y = i % this.sizeY;
			this.z = i / this.sizeY;
			this.blocksIterated++;
			return true;
		}
	}

	public int getX() {
		return this.startX + this.x;
	}

	public int getY() {
		return this.startY + this.y;
	}

	public int getZ() {
		return this.startZ + this.z;
	}

	public int getEdgeCoordinatesCount() {
		int i = 0;
		if (this.x == 0 || this.x == this.sizeX - 1) {
			i++;
		}

		if (this.y == 0 || this.y == this.sizeY - 1) {
			i++;
		}

		if (this.z == 0 || this.z == this.sizeZ - 1) {
			i++;
		}

		return i;
	}

	public static CuboidBlockIterator iterator(final Box box) {
		int i = (int) Math.floor(box.minX - 1.0E-7) - 1;
		int j = (int) Math.floor(box.maxX + 1.0E-7) + 1;
		int k = (int) Math.floor(box.minY - 1.0E-7) - 1;
		int l = (int) Math.floor(box.maxY + 1.0E-7) + 1;
		int m = (int) Math.floor(box.minZ - 1.0E-7) - 1;
		int n = (int) Math.floor(box.maxZ + 1.0E-7) + 1;
		return new CuboidBlockIterator(i, k, m, j, l, n);
	}
}
