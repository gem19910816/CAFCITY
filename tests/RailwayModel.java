// Offline model adapted from Lost Cities 1.21-8.4.6 Railway logic.
// Uses the installed Lost Cities QualityRandom; fixes cityLevel=0 and a single circular city.
// It does not invoke Minecraft world generation or model highways/neighbor ownership.
import java.util.*;import java.util.concurrent.*;import mcjty.lostcities.api.RailChunkType;import mcjty.lostcities.config.LostCityProfile;import mcjty.lostcities.varia.QualityRandom;import mcjty.lostcities.worldgen.lost.Railway.RailChunkInfo;import mcjty.lostcities.worldgen.lost.Railway.RailDirection;import mcjty.lostcities.worldgen.lost.regassets.data.RailwayParts;
public class RailwayModel {
    public static final int RAILWAY_LEVEL_OFFSET = -3;
    private static final Map<Coord, RailChunkInfo> RAIL_INFO = new ConcurrentHashMap<Coord, RailChunkInfo>();

    public static int getRailwayLevel(LostCityProfile profile) {
        return -3 + profile.RAILWAY_LEVEL_OFFSET;
    }

    public static void cleanCache() {
        RAIL_INFO.clear();
    }

    private static RailChunkInfo getRailChunkTypeInternal(Coord key, Provider provider) {
        int chunkX = key.chunkX();
        int chunkZ = key.chunkZ();
        QualityRandom randomRailChunkType = new QualityRandom(provider.getSeed() + (long)chunkZ * 2600003897L + (long)chunkX * 43600002517L);
        LostCityProfile profile = provider.getProfile();
        int railwayLevel = RailwayModel.getRailwayLevel(profile);
        RailwayParts railwayParts = RailwayParts.DEFAULT;
        float r = randomRailChunkType.nextFloat();
        int spacingX = profile.RAILWAY_SPACING_EAST_WEST;
        int spacingZ = profile.RAILWAY_SPACING_NORTH_SOUTH;
        int halfSpacingX = spacingX / 2;
        int mx = Math.floorMod(chunkX + 1, spacingX * 2);
        int mz = Math.floorMod(chunkZ + 1, spacingZ * 2);
        if (mx == 0 && mz == spacingZ) {
            if (!isCity(key, provider, profile)) {
                if (profile.RAILWAYS_CAN_END) {
                    boolean cityWest;
                    boolean cityEast = isCity(key.offset(spacingX, 0), provider, profile) || isCity(key.offset(spacingX, -spacingZ), provider, profile) || isCity(key.offset(spacingX, spacingZ), provider, profile);
                    boolean bl = cityWest = isCity(key.offset(spacingX, 0), provider, profile) || isCity(key.offset(-spacingX, -spacingZ), provider, profile) || isCity(key.offset(-spacingX, spacingZ), provider, profile);
                    if (!cityEast && !cityWest) {
                        return RailChunkInfo.NOTHING;
                    }
                    if (!cityEast) {
                        return new RailChunkInfo(RailChunkType.RAILS_END_HERE, RailDirection.WEST, railwayLevel, 3);
                    }
                    if (!cityWest) {
                        return new RailChunkInfo(RailChunkType.RAILS_END_HERE, RailDirection.EAST, railwayLevel, 3);
                    }
                }
                return new RailChunkInfo(RailChunkType.HORIZONTAL, RailDirection.BI, railwayLevel, 3);
            }
            return RailwayModel.getStationType(key, provider, profile, r, 3, randomRailChunkType.nextFloat() < 0.5f ? railwayParts.stationOpen() : railwayParts.stationOpenRoof());
        }
        if (mx == spacingX && mz == 0) {
            if (!isCity(key, provider, profile)) {
                if (profile.RAILWAYS_CAN_END) {
                    boolean cityWest;
                    boolean cityEast = isCity(key.offset(spacingX, -spacingZ), provider, profile) || isCity(key.offset(spacingX, spacingZ), provider, profile);
                    boolean bl = cityWest = isCity(key.offset(-spacingX, -spacingZ), provider, profile) || isCity(key.offset(-spacingX, spacingZ), provider, profile);
                    if (!cityEast && !cityWest) {
                        return RailChunkInfo.NOTHING;
                    }
                    if (!cityEast) {
                        return new RailChunkInfo(RailChunkType.RAILS_END_HERE, RailDirection.WEST, railwayLevel, 2);
                    }
                    if (!cityWest) {
                        return new RailChunkInfo(RailChunkType.RAILS_END_HERE, RailDirection.EAST, railwayLevel, 2);
                    }
                }
                return new RailChunkInfo(RailChunkType.HORIZONTAL, RailDirection.BI, railwayLevel, 2);
            }
            return RailwayModel.getStationType(key, provider, profile, r, 2, randomRailChunkType.nextFloat() < 0.5f ? railwayParts.stationOpen() : railwayParts.stationOpenRoof());
        }
        if (mx == spacingX && mz == spacingZ) {
            if (!isCity(key, provider, profile)) {
                if (profile.RAILWAYS_CAN_END) {
                    boolean cityEast = isCity(key.offset(spacingX, 0), provider, profile);
                    boolean cityWest = isCity(key.offset(-spacingX, 0), provider, profile);
                    if (!cityEast && !cityWest) {
                        return RailChunkInfo.NOTHING;
                    }
                    if (!cityEast) {
                        return new RailChunkInfo(RailChunkType.RAILS_END_HERE, RailDirection.WEST, railwayLevel, 1);
                    }
                    if (!cityWest) {
                        return new RailChunkInfo(RailChunkType.RAILS_END_HERE, RailDirection.EAST, railwayLevel, 1);
                    }
                }
                return new RailChunkInfo(RailChunkType.HORIZONTAL, RailDirection.BI, railwayLevel, 1);
            }
            return RailwayModel.getStationType(key, provider, profile, r, 1, randomRailChunkType.nextFloat() < 0.5f ? railwayParts.stationOpen() : railwayParts.stationOpenRoof());
        }
        if (mx == 0 && mz == 0) {
            return RailChunkInfo.NOTHING;
        }
        if (mz == 0 || mz == spacingZ) {
            if (mx >= spacingX + halfSpacingX + 1 && mz != 0 || mx >= halfSpacingX + 1 && mx < spacingX) {
                Coord east = key.east();
                RailChunkInfo adjacent = RailwayModel.getRailChunkType(east, provider, profile);
                RailDirection direction = adjacent.getDirection();
                if (direction == RailDirection.BI || adjacent.getType() == RailChunkType.RAILS_END_HERE) {
                    direction = RailDirection.WEST;
                }
                return RailwayModel.testAdjacentRailChunk(r, adjacent, direction, key.west(), provider, profile);
            }
            if (mx >= 1 && mx < halfSpacingX && mz != 0 || mx > spacingX && mx < spacingX + halfSpacingX) {
                Coord west = key.west();
                RailChunkInfo adjacent = RailwayModel.getRailChunkType(west, provider, profile);
                RailDirection direction = adjacent.getDirection();
                if (direction == RailDirection.BI || adjacent.getType() == RailChunkType.RAILS_END_HERE) {
                    direction = RailDirection.EAST;
                }
                return RailwayModel.testAdjacentRailChunk(r, adjacent, direction, key.east(), provider, profile);
            }
            if (mz == 0 && mx == halfSpacingX) {
                if (profile.RAILWAYS_CAN_END) {
                    boolean cityWest = isCity(key.offset(-halfSpacingX, -spacingZ), provider, profile) || isCity(key.offset(-halfSpacingX, spacingZ), provider, profile);
                    boolean cityEast = isCity(key.offset(halfSpacingX, 0), provider, profile);
                    if (!cityEast && !cityWest) {
                        return RailChunkInfo.NOTHING;
                    }
                }
                return new RailChunkInfo(RailChunkType.DOUBLE_BEND, RailDirection.EAST, railwayLevel, 1);
            }
            if (mz == 0 && mx == spacingX + halfSpacingX) {
                if (profile.RAILWAYS_CAN_END) {
                    boolean cityEast = isCity(key.offset(halfSpacingX, -spacingZ), provider, profile) || isCity(key.offset(halfSpacingX, spacingZ), provider, profile);
                    boolean cityWest = isCity(key.offset(-halfSpacingX, 0), provider, profile);
                    if (!cityEast && !cityWest) {
                        return RailChunkInfo.NOTHING;
                    }
                }
                return new RailChunkInfo(RailChunkType.DOUBLE_BEND, RailDirection.WEST, railwayLevel, 1);
            }
            if (mz == spacingZ && mx == halfSpacingX) {
                if (profile.RAILWAYS_CAN_END) {
                    RailChunkInfo typeSouth;
                    RailChunkInfo typeNorth;
                    boolean cityEast = isCity(key.offset(halfSpacingX, 0), provider, profile);
                    boolean cityWest = isCity(key.offset(-halfSpacingX, 0), provider, profile);
                    if (!cityEast && !cityWest && (typeNorth = RailwayModel.getRailChunkType(key.offset(0, -spacingZ), provider, profile)).getType() == RailChunkType.NONE && (typeSouth = RailwayModel.getRailChunkType(key.offset(0, spacingZ), provider, profile)).getType() == RailChunkType.NONE) {
                        return RailChunkInfo.NOTHING;
                    }
                }
                return new RailChunkInfo(RailChunkType.THREE_SPLIT, RailDirection.EAST, railwayLevel, 3);
            }
            if (mz == spacingZ && mx == spacingX + halfSpacingX) {
                if (profile.RAILWAYS_CAN_END) {
                    RailChunkInfo typeSouth;
                    RailChunkInfo typeNorth;
                    boolean cityEast = isCity(key.offset(halfSpacingX, 0), provider, profile);
                    boolean cityWest = isCity(key.offset(-halfSpacingX, 0), provider, profile);
                    if (!cityEast && !cityWest && (typeNorth = RailwayModel.getRailChunkType(key.offset(0, -spacingZ), provider, profile)).getType() == RailChunkType.NONE && (typeSouth = RailwayModel.getRailChunkType(key.offset(0, spacingZ), provider, profile)).getType() == RailChunkType.NONE) {
                        return RailChunkInfo.NOTHING;
                    }
                }
                return new RailChunkInfo(RailChunkType.THREE_SPLIT, RailDirection.WEST, railwayLevel, 3);
            }
            return RailChunkInfo.NOTHING;
        }
        if (mx == halfSpacingX) {
            if (profile.RAILWAYS_CAN_END) {
                RailChunkInfo typeNorth = RailwayModel.getRailChunkType(key.offset(0, -(mz % spacingZ)), provider, profile);
                RailChunkInfo typeSouth = RailwayModel.getRailChunkType(key.offset(0, -(mz % spacingZ) + spacingZ), provider, profile);
                if (typeNorth.getType() == RailChunkType.NONE || typeSouth.getType() == RailChunkType.NONE) {
                    return RailChunkInfo.NOTHING;
                }
            }
            return new RailChunkInfo(RailChunkType.VERTICAL, RailDirection.EAST, railwayLevel, 1);
        }
        if (mx == spacingX + halfSpacingX) {
            if (profile.RAILWAYS_CAN_END) {
                RailChunkInfo typeNorth = RailwayModel.getRailChunkType(key.offset(0, -(mz % spacingZ)), provider, profile);
                RailChunkInfo typeSouth = RailwayModel.getRailChunkType(key.offset(0, -(mz % spacingZ) + spacingZ), provider, profile);
                if (typeNorth.getType() == RailChunkType.NONE || typeSouth.getType() == RailChunkType.NONE) {
                    return RailChunkInfo.NOTHING;
                }
            }
            return new RailChunkInfo(RailChunkType.VERTICAL, RailDirection.WEST, railwayLevel, 1);
        }
        return RailChunkInfo.NOTHING;
    }

    private static RailChunkInfo getStationType(Coord coord, Provider provider, LostCityProfile profile, float r, int rails, List<String> part) {
        int cityLevel = 0;
        int railwayLevel = RailwayModel.getRailwayLevel(profile);
        int maximumDescent = profile.RAILWAY_SPACING_EAST_WEST - 2;
        if (cityLevel > 2 || cityLevel - railwayLevel > maximumDescent || !profile.RAILWAY_SURFACE_STATIONS_ENABLED) {
            return new RailChunkInfo(RailChunkType.STATION_UNDERGROUND, RailDirection.BI, railwayLevel, rails);
        }
        int highwayX = -1;
        int highwayZ = -1;
        if (highwayX != -1 && cityLevel >= highwayX || highwayZ != -1 && cityLevel >= highwayZ) {
            return new RailChunkInfo(RailChunkType.STATION_UNDERGROUND, RailDirection.BI, railwayLevel, rails);
        }
        highwayZ = -1;
        if (highwayZ != -1 && cityLevel >= highwayZ) {
            return new RailChunkInfo(RailChunkType.STATION_UNDERGROUND, RailDirection.BI, railwayLevel, rails);
        }
        highwayZ = -1;
        if (highwayZ != -1 && cityLevel >= highwayZ) {
            return new RailChunkInfo(RailChunkType.STATION_UNDERGROUND, RailDirection.BI, railwayLevel, rails);
        }
        return r < 0.5f ? new RailChunkInfo(RailChunkType.STATION_SURFACE, RailDirection.BI, cityLevel, rails, part) : new RailChunkInfo(RailChunkType.STATION_UNDERGROUND, RailDirection.BI, railwayLevel, rails);
    }

    public static RailChunkInfo getRailChunkType(Coord coord, Provider provider, LostCityProfile profile) {
        RailChunkInfo cached = RAIL_INFO.get(coord);
        if (cached != null) {
            return cached;
        }
        RailChunkInfo info = RailwayModel.getRailChunkTypeInternal(coord, provider);
        if (info.getType().isStation()) {
            if (!profile.RAILWAY_STATIONS_ENABLED) {
                info = RailChunkInfo.NOTHING;
            }
        } else if (!profile.RAILWAYS_ENABLED) {
            info = RailChunkInfo.NOTHING;
        }
        RailChunkInfo existing = RAIL_INFO.putIfAbsent(coord, info);
        return existing == null ? info : existing;
    }

    public static void removeRailChunkType(Coord coord) {
        RAIL_INFO.put(coord, RailChunkInfo.NOTHING);
    }

    private static RailChunkInfo testAdjacentRailChunk(float r, RailChunkInfo adjacent, RailDirection direction, Coord coord, Provider provider, LostCityProfile profile) {
        int railwayLevel = RailwayModel.getRailwayLevel(profile);
        switch (adjacent.getType()) {
            case NONE: {
                return RailChunkInfo.NOTHING;
            }
            case STATION_SURFACE: {
                int highwayX = -1;
                int highwayZ = -1;
                if (highwayX != -1 && adjacent.getLevel() == highwayX || highwayZ != -1 && adjacent.getLevel() == highwayZ) {
                    r = 1.0f;
                }
                int maximumDescentAfterExtension = profile.RAILWAY_SPACING_EAST_WEST - 4;
                if (r < 0.4f && adjacent.getLevel() - railwayLevel <= maximumDescentAfterExtension) {
                    return new RailChunkInfo(RailChunkType.STATION_EXTENSION_SURFACE, direction, adjacent.getLevel(), adjacent.getRails());
                }
                return RailwayModel.startDescending(adjacent, direction, railwayLevel);
            }
            case STATION_UNDERGROUND: {
                return r < 0.4f ? new RailChunkInfo(RailChunkType.STATION_EXTENSION_UNDERGROUND, direction, adjacent.getLevel(), adjacent.getRails()) : new RailChunkInfo(RailChunkType.HORIZONTAL, direction, adjacent.getLevel(), adjacent.getRails());
            }
            case STATION_EXTENSION_SURFACE: {
                return RailwayModel.startDescending(adjacent, direction, railwayLevel);
            }
            case STATION_EXTENSION_UNDERGROUND: {
                return new RailChunkInfo(RailChunkType.HORIZONTAL, direction, adjacent.getLevel(), adjacent.getRails());
            }
            case GOING_DOWN_FURTHER: 
            case GOING_DOWN_ONE_FROM_SURFACE: 
            case GOING_DOWN_TWO_FROM_SURFACE: {
                if (adjacent.getLevel() == railwayLevel) {
                    return new RailChunkInfo(RailChunkType.HORIZONTAL, direction, adjacent.getLevel(), adjacent.getRails());
                }
                return new RailChunkInfo(RailChunkType.GOING_DOWN_FURTHER, direction, adjacent.getLevel() - 2, adjacent.getRails());
            }
            case THREE_SPLIT: {
                break;
            }
            case VERTICAL: {
                break;
            }
            case DOUBLE_BEND: {
                break;
            }
            case RAILS_END_HERE: {
                if (direction == adjacent.getDirection()) {
                    return new RailChunkInfo(RailChunkType.HORIZONTAL, direction, adjacent.getLevel(), adjacent.getRails());
                }
                return RailChunkInfo.NOTHING;
            }
            case HORIZONTAL: {
                return adjacent;
            }
        }
        throw new RuntimeException("This is really impossible!");
    }

    private static RailChunkInfo startDescending(RailChunkInfo adjacent, RailDirection direction, int railwayLevel) {
        int difference = adjacent.getLevel() - railwayLevel;
        if ((difference & 1) == 1) {
            return new RailChunkInfo(RailChunkType.GOING_DOWN_ONE_FROM_SURFACE, direction, adjacent.getLevel() - 1, adjacent.getRails());
        }
        return new RailChunkInfo(RailChunkType.GOING_DOWN_TWO_FROM_SURFACE, direction, adjacent.getLevel() - 2, adjacent.getRails());
    }


 public record Coord(int chunkX,int chunkZ){public Coord offset(int x,int z){return new Coord(chunkX+x,chunkZ+z);}public Coord east(){return offset(1,0);}public Coord west(){return offset(-1,0);}}
 public static class Provider {final long seed;final LostCityProfile profile;final int centerX,centerZ,radius;Provider(long s,LostCityProfile p,int x,int z,int r){seed=s;profile=p;centerX=x;centerZ=z;radius=r;}public long getSeed(){return seed;}public LostCityProfile getProfile(){return profile;}}
 static boolean isCity(Coord c,Provider p,LostCityProfile f){long x=c.chunkX-p.centerX,z=c.chunkZ-p.centerZ;return x*x+z*z<(long)p.radius*p.radius;}
}
