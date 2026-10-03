package com.mozhi.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI;
import com.fs.starfarer.api.campaign.econ.*;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.FactoryAPI;
import com.fs.starfarer.api.fleet.*;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.util.MutableValue;
import com.mozhi.fleet.execution.FleetPlanExecutor;
import com.mozhi.fleet.game.FleetDestinations;
import com.mozhi.fleet.game.FleetWorld;
import com.mozhi.fleet.model.FleetPlan;
import com.mozhi.fleet.model.FleetPlanStep;
import com.mozhi.fleet.model.FleetState;
import com.mozhi.fleet.trade.FleetTrading;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiFunction;
import org.lwjgl.util.vector.Vector2f;

/** 用可变假库存验证实物转移、结算和执行次序，不启动游戏。 */
public final class FleetTradingChecks {
    private record Item(CargoAPI.CargoItemType type, Object data) {}
    private static final Item SUPPLIES=new Item(CargoAPI.CargoItemType.RESOURCES,"supplies");
    private static final Item SPECIAL=new Item(CargoAPI.CargoItemType.SPECIAL,new SpecialItemData("blueprint","test_hull"));

    public static void main(String[] args) throws Exception {
        SettingsAPI previous=Global.getSettings();
        FactoryAPI previousFactory=Global.getFactory();
        try {
            Global.setSettings(proxy(SettingsAPI.class,(m,a)->m.equals("getFloat")
                    ? ((String)a[0]).endsWith("BuyPriceMult")?1.2f:0.8f:null));
            Global.setFactory(proxy(FactoryAPI.class,(m,a)->m.equals("createPerson")?proxy(PersonAPI.class,(name,values)->null):null));
            trades(); ships(); rollback(); navigation();
        } finally {Global.setSettings(previous); Global.setFactory(previousFactory);}
        System.out.println("PASS: live inventory, buy/sell and tariffs, no cash reserve, multi-shop stock, special instance data, real ship transfer, rollback, navigation/orbit, save/load no duplicate trade");
    }
    private static void trades() {
        World world=new World();
        world.fleet.cargo.money.set(600);
        FleetTrading.execute(world.fleet.api,world.market,trade("补给",5,true));
        check(world.shop.quantity(SUPPLIES)==15 && world.fleet.cargo.quantity(SUPPLIES)==5,"Actual stock moves by name");
        check(world.fleet.cargo.money.get()==0,"Spend entire balance, no reserve or capacity check");
        FleetTrading.execute(world.fleet.api,world.market,trade("supplies",2,false));
        check(world.shop.quantity(SUPPLIES)==17 && world.fleet.cargo.quantity(SUPPLIES)==3,"Sell removes own cargo and adds market stock");
        check(world.fleet.cargo.money.get()==128,"Sale uses demand price minus tariff");
        world.fleet.cargo.money.set(1);
        fails(() -> FleetTrading.execute(world.fleet.api,world.market,trade("supplies",1,true)));
        check(world.shop.quantity(SUPPLIES)==17 && world.fleet.cargo.quantity(SUPPLIES)==3,"Insufficient funds cause no transfer");
        world.fleet.cargo.money.set(100000);
        world.shop.items.put(SUPPLIES,1f); // Change live stock after previous purchase.
        fails(() -> FleetTrading.execute(world.fleet.api,world.market,trade("supplies",2,true)));
        check(world.shop.quantity(SUPPLIES)==1,"Cannot buy stale stock");
        world.shop.items.put(SPECIAL,1f);
        FleetTrading.execute(world.fleet.api,world.market,trade("blueprint:test_hull",1,true));
        check(world.fleet.cargo.quantity(SPECIAL)==1 && world.shop.quantity(SPECIAL)==0,"Retain special item data");

        Cargo second=new Cargo(); second.items.put(SUPPLIES,3f);
        world.shops.add(shop(second,"black_market",0,false));
        float before=world.fleet.cargo.quantity(SUPPLIES);
        FleetTrading.execute(world.fleet.api,world.market,trade("supplies",4,true));
        check(world.fleet.cargo.quantity(SUPPLIES)==before+4 && second.quantity(SUPPLIES)==0,"Combine actual stock across shops");
        Cargo warehouse=new Cargo(); warehouse.items.put(SUPPLIES,999f);
        world.shops.add(shop(warehouse,"storage",0,true));
        fails(() -> FleetTrading.execute(world.fleet.api,world.market,trade("supplies",1,true)));
        check(warehouse.quantity(SUPPLIES)==999,"Never buy from free storage");
    }
    private static void ships() {
        World world=new World();
        Ship ship=new Ship("for-sale","锤头");
        world.shop.ships.members.add(ship.api); world.fleet.cargo.money.set(1200);
        FleetTrading.execute(world.fleet.api,world.market,trade("锤头",1,true));
        check(world.fleet.ships.members.contains(ship.api) && !world.shop.ships.members.contains(ship.api),"Move original ship object, not generated variant");
        check(!ship.mothballed && world.fleet.cargo.money.get()==0,"Activate actual purchased ship and charge including tariff");
        FleetTrading.execute(world.fleet.api,world.market,trade("for-sale",1,false));
        check(!world.fleet.ships.members.contains(ship.api) && world.shop.ships.members.contains(ship.api)
                && ship.mothballed && world.fleet.cargo.money.get()==400,"Sell original ship back and credit sell value minus tariff");
        fails(() -> FleetTrading.execute(world.fleet.api,world.market,trade("own",1,false)));
    }
    private static void rollback() {
        World world=new World(); world.fleet.cargo.money.set(1000);
        world.fleet.cargo.failNextAdd=true;
        fails(() -> FleetTrading.execute(world.fleet.api,world.market,trade("supplies",3,true)));
        check(world.shop.quantity(SUPPLIES)==20 && world.fleet.cargo.quantity(SUPPLIES)==0
                && world.fleet.cargo.money.get()==1000,"Restore debit source and credits on failed cargo addition");
        Ship ship=new Ship("rollback-ship","锤头"); world.shop.ships.members.add(ship.api);
        world.fleet.cargo.money.set(2000); world.fleet.failSync=true;
        fails(() -> FleetTrading.execute(world.fleet.api,world.market,trade("rollback-ship",1,true)));
        check(world.shop.ships.members.contains(ship.api) && !world.fleet.ships.members.contains(ship.api)
                && ship.mothballed && world.fleet.cargo.money.get()==2000,"Restore ship ownership, mothballing and credits after late failure");
    }
    private static void navigation() throws Exception {
        World world=new World(); SectorAPI old=Global.getSector();
        try {
            Global.setSector(world.sector);
            check(FleetDestinations.resolve("jangala",true).entity()==world.planet,"Resolve market ID");
            check(FleetDestinations.resolve("JANGALA",true).entity()==world.planet,"Resolve normalized name/ID");
            check(FleetDestinations.resolve("Corvus",false).system(),"Resolve system name");
            fails(() -> FleetDestinations.resolve("Corvus",true));
            check(FleetDestinations.resolve("remote",true).entity()==world.remote,"Include entity-bound markets outside economy/current location");
            FleetPlanExecutor executor=new FleetPlanExecutor();
            FleetState invalid=new FleetState();
            invalid.plan=FleetPlan.visit(FleetPlanStep.Action.BUY,"jangala","","supplies",2);
            invalid.plan.prepare(0,0);
            world.fleet.position.set(5000,0);
            fails(() -> executor.advance(world.fleet.api,invalid,1));
            check(world.fleet.assignment==null && world.shop.quantity(SUPPLIES)==20,
                    "BUY without MOVE_TO must fail without assigning travel or orbit");
            invalid.plan=FleetPlan.visit(FleetPlanStep.Action.SELL,"jangala","","supplies",1);
            invalid.plan.prepare(0,0);
            fails(() -> executor.advance(world.fleet.api,invalid,1));
            check(world.fleet.assignment==null,"SELL must not navigate either");
            FleetPlan plan=FleetPlan.visit(FleetPlanStep.Action.MOVE_TO,"jangala","","",0);
            plan.steps.add(trade("supplies",2,true));
            plan.steps.add(FleetPlanStep.visit(FleetPlanStep.Action.SELL,"jangala","","supplies",1));
            plan.steps.add(new FleetPlanStep(FleetPlanStep.Action.RETURN,0,"返回"));
            plan.prepare(0,0); // zero-duration navigation/trade is allowed before RETURN.
            FleetState state=new FleetState(); state.plan=plan;
            world.fleet.cargo.money.set(1000); world.fleet.position.set(5000,0);
            executor.advance(world.fleet.api,state,1);
            check(world.fleet.assignment==FleetAssignment.GO_TO_LOCATION && world.shop.quantity(SUPPLIES)==20,"Travel first, no remote purchase");
            world.fleet.position.set(0,0);
            executor.advance(world.fleet.api,state,1);
            check(world.fleet.assignment==FleetAssignment.ORBIT_PASSIVE && world.shop.quantity(SUPPLIES)==20,"Switch to orbit before trade");
            executor.advance(world.fleet.api,state,1);
            check(state.plan.currentStep==0 && world.shop.quantity(SUPPLIES)==20,"Orbit assignment alone does not complete MOVE_TO");
            world.fleet.orbitFocus=world.planet; // Simulate the engine actually establishing an orbit.
            executor.advance(world.fleet.api,state,1);
            check(state.plan.currentStep==1 && world.shop.quantity(SUPPLIES)==20,"MOVE_TO completes only after actual orbit, without trading");
            FleetWorld.holdPosition(world.fleet.api);
            check(FleetWorld.isOrbiting(world.fleet.api,world.planet),"New planning/load retains existing orbit");
            executor.advance(world.fleet.api,state,1);
            check(state.plan.currentStep==2 && world.shop.quantity(SUPPLIES)==18,"Separate BUY step purchases once");
            ObjectMapper json=new ObjectMapper(); state=FleetState.restore(json,json.writeValueAsString(state));
            executor.advance(world.fleet.api,state,1);
            check(state.plan.currentStep==3 && world.shop.quantity(SUPPLIES)==19,"Resume SELL without repeating BUY or moving");
            check(world.fleet.cargo.quantity(SUPPLIES)==1 && world.fleet.cargo.money.get()==824,"Combined plan accounting");

            state=new FleetState(); state.plan=FleetPlan.visit(FleetPlanStep.Action.MOVE_TO,"Corvus","","",0);
            state.plan.prepare(0,0); world.fleet.location=world.otherLocation;
            executor.advance(world.fleet.api,state,1);
            check(state.plan.active(),"Do not complete system travel while still outside");
            world.fleet.location=world.location; world.fleet.position.set(20000,0);
            executor.advance(world.fleet.api,state,1);
            check(state.plan.active(),"Entering a system alone is not enough, reach and orbit its center");
            world.fleet.position.set(0,0); executor.advance(world.fleet.api,state,1);
            check(state.plan.active(),"Wait for native orbit even when close");
            world.fleet.orbitFocus=world.planet;
            executor.advance(world.fleet.api,state,1); executor.advance(world.fleet.api,state,1);
            check(state.plan.status==FleetPlan.Status.COMPLETED && world.fleet.assignment==FleetAssignment.ORBIT_PASSIVE,
                    "Confirmed system-center orbit completes MOVE_TO and stays intact when idle");
            invalid.plan=FleetPlan.visit(FleetPlanStep.Action.BUY,"remote","","supplies",1);
            invalid.plan.prepare(0,0);
            fails(() -> executor.advance(world.fleet.api,invalid,1));
            check(world.fleet.target==world.planet && world.shop.quantity(SUPPLIES)==19,"Orbiting another market cannot authorize trade or trigger navigation");
        } finally { Global.setSector(old); }
    }
    private static FleetPlanStep trade(String item,int quantity,boolean buy) {
        return FleetPlanStep.visit(buy?FleetPlanStep.Action.BUY:FleetPlanStep.Action.SELL,"jangala","",item,quantity);
    }
    static final class Cargo {
        final Map<Item,Float> items=new LinkedHashMap<>();
        final MutableValue money=new MutableValue();
        final Ships ships=new Ships();
        boolean failNextAdd;
        final CargoAPI api=proxy(CargoAPI.class,(method,args)-> switch(method) {
            case "getCredits" -> money;
            case "getMothballedShips" -> ships.api;
            case "getStacksCopy" -> items.entrySet().stream().filter(e->e.getValue()>0).map(e->stack(e.getKey())).toList();
            case "getQuantity" -> quantity(new Item((CargoAPI.CargoItemType)args[0],args[1]));
            case "removeItems", "addItems" -> {
                if(method.equals("addItems") && failNextAdd) { failNextAdd=false; throw new IllegalStateException("simulated write failure"); }
                Item item=new Item((CargoAPI.CargoItemType)args[0],args[1]);
                float delta=(float)args[2]*(method.equals("removeItems")?-1:1);
                items.put(item,quantity(item)+delta); yield method.equals("removeItems")?true:null;
            }
            default -> null;
        });
        float quantity(Item item) {return items.getOrDefault(item,0f);}
        CargoStackAPI stack(Item item) {
            return proxy(CargoStackAPI.class,(method,args)-> switch(method) {
                case "getSize" -> quantity(item); case "getType" -> item.type(); case "getData" -> item.data();
                case "isCommodityStack" -> item.type()==CargoAPI.CargoItemType.RESOURCES;
                case "isSpecialStack" -> item.type()==CargoAPI.CargoItemType.SPECIAL;
                case "getCommodityId" -> item.data(); case "getSpecialDataIfSpecial" -> item.data();
                case "getBaseValuePerUnit" -> 100;
                case "getDisplayName" -> item.equals(SUPPLIES)?"补给":"蓝图";
                default -> null;
            });
        }
    }
    static final class Ships {
        final List<FleetMemberAPI> members=new ArrayList<>();
        final FleetDataAPI api=proxy(FleetDataAPI.class,(method,args)->switch(method) {
            case "getMembersListCopy" -> new ArrayList<>(members); case "getNumMembers" -> members.size();
            case "addFleetMember" -> {members.add((FleetMemberAPI)args[0]); yield null;}
            case "removeFleetMember" -> {members.remove(args[0]); yield null;}
            default -> null;
        });
    }
    private static final class Ship {
        boolean mothballed=true;
        final FleetMemberAPI api;
        Ship(String id,String name) {
            ShipHullSpecAPI hull=proxy(ShipHullSpecAPI.class,(m,a)->switch(m) {
                case "getHullId","getBaseHullId" -> "hammerhead"; case "getHullName" -> name; default -> null;
            });
            RepairTrackerAPI repair=proxy(RepairTrackerAPI.class,(m,a)-> {
                if(m.equals("setMothballed")) mothballed=(boolean)a[0]; return null;
            });
            api=proxy(FleetMemberAPI.class,(m,a)->switch(m) {
                case "getId","getShipName" -> id; case "getHullSpec" -> hull;
                case "getHullId" -> "hammerhead";
                case "getBaseBuyValue" -> 1000f; case "getBaseSellValue" -> 500f;
                case "isMothballed" -> mothballed; case "getRepairTracker" -> repair; default -> null;
            });
        }
    }
    static final class Fleet {
        final Cargo cargo=new Cargo(); final Ships ships=new Ships();
        final Vector2f position=new Vector2f();
        LocationAPI location; FleetAssignment assignment; SectorEntityToken target,orbitFocus; boolean failSync;
        final CampaignFleetAPI api;
        Fleet() {
            ships.members.add(new Ship("own","自有舰船").api);
            CampaignFleetAIAPI ai=proxy(CampaignFleetAIAPI.class,(m,a)->m.equals("getCurrentAssignment") && assignment!=null
                    ?proxy(FleetAssignmentDataAPI.class,(method,args)-> switch(method) {
                        case "getAssignment" -> assignment; case "getTarget" -> target; default -> null;
                    }):null);
            MemoryAPI memory=proxy(MemoryAPI.class,(m,a)->null);
            api=proxy(CampaignFleetAPI.class,(m,a)->switch(m) {
                case "getCargo" -> cargo.api; case "getFleetData" -> ships.api; case "getAI" -> ai;
                case "getId" -> "test-fleet";
                case "getLogistics" -> proxy(com.fs.starfarer.api.fleet.FleetLogisticsAPI.class,(method,args)->null);
                case "getLocation" -> position; case "getContainingLocation" -> location; case "getRadius" -> 20f;
                case "getOrbit" -> orbitFocus==null?null:proxy(OrbitAPI.class,(method,args)->method.equals("getFocus")?orbitFocus:null);
                case "getMemoryWithoutUpdate" -> memory;
                case "clearAssignments" -> {assignment=null; target=null; orbitFocus=null; yield null;}
                case "addAssignment" -> {assignment=(FleetAssignment)a[0]; target=(SectorEntityToken)a[1]; yield null;}
                case "forceSync" -> {if(failSync)throw new IllegalStateException("sync failure"); yield null;}
                default -> null;
            });
        }
    }
    private static SubmarketAPI shop(Cargo cargo,String id,float tariff,boolean free) {
        SubmarketPlugin plugin=proxy(SubmarketPlugin.class,(m,a)->switch(m) {
            case "isFreeTransfer" -> free; case "isOpenMarket" -> id.equals("open_market");
            case "isBlackMarket" -> id.equals("black_market"); default -> null;
        });
        return proxy(SubmarketAPI.class,(m,a)->switch(m) {
            case "getCargo" -> cargo.api; case "getPlugin" -> plugin; case "getSpecId","getNameOneLine" -> id;
            case "getTariff" -> tariff; default -> null;
        });
    }
    static final class World {
        final Fleet fleet=new Fleet(); final Cargo shop=new Cargo();
        final List<SubmarketAPI> shops=new ArrayList<>();
        final MarketAPI market; final SectorEntityToken planet,remote;
        final LocationAPI location,otherLocation; final SectorAPI sector;
        World() {
            List<SectorEntityToken> entities=new ArrayList<>(), remoteEntities=new ArrayList<>();
            location=proxy(LocationAPI.class,(m,a)->switch(m) {
                case "getAllEntities" -> entities; case "getFleets" -> List.of(fleet.api); case "getName","getId" -> "local"; default -> null;
            });
            otherLocation=proxy(LocationAPI.class,(m,a)->switch(m) {
                case "getAllEntities" -> remoteEntities; case "getFleets" -> List.of(); case "getName","getId" -> "other"; default -> null;
            });
            fleet.location=location; shop.items.put(SUPPLIES,20f); shops.add(shop(shop,"open_market",0.2f,false));
            SectorEntityToken[] reference=new SectorEntityToken[1];
            market=proxy(MarketAPI.class,(m,a)->switch(m) {
                case "getId","getName" -> "jangala"; case "getPrimaryEntity" -> reference[0];
                case "getSubmarketsCopy" -> shops;
                case "getSupplyPrice" -> (float)((double)a[1]*100);
                case "getDemandPrice" -> (float)((double)a[1]*80); default -> null;
            });
            planet=proxy(PlanetAPI.class,(m,a)->switch(m) {
                case "getId" -> "jangala-entity"; case "getName" -> "Jangala"; case "getMarket" -> market;
                case "getContainingLocation" -> location; case "getLocation" -> new Vector2f(); case "getRadius" -> 100f; default -> null;
            });
            reference[0]=planet; entities.add(planet);
            SectorEntityToken[] remoteRef=new SectorEntityToken[1];
            MarketAPI remoteMarket=proxy(MarketAPI.class,(m,a)->switch(m) {
                case "getId","getName" -> "remote"; case "getPrimaryEntity" -> remoteRef[0]; default -> null;
            });
            remote=proxy(SectorEntityToken.class,(m,a)->switch(m) {
                case "getId","getName" -> "remote-entity"; case "getMarket" -> remoteMarket; default -> null;
            });
            remoteRef[0]=remote; remoteEntities.add(remote);
            StarSystemAPI system=proxy(StarSystemAPI.class,(m,a)->switch(m) {
                case "getId" -> "corvus-id"; case "getName","getBaseName" -> "Corvus";
                case "getCenter" -> planet; case "getPlanets" -> List.of(planet); default -> null;
            });
            EconomyAPI economy=proxy(EconomyAPI.class,(m,a)->m.equals("getMarketsCopy")?List.of(market):null);
            Map<String,Object> persistent=new HashMap<>();
            sector=proxy(SectorAPI.class,(m,a)->switch(m) {
                case "getPersistentData" -> persistent; case "isPaused" -> true;
                case "getEconomy" -> economy; case "getAllLocations" -> List.of(location,otherLocation);
                case "getStarSystems" -> List.of(system); case "getPlayerFleet" -> fleet.api;
                case "getEntityById" -> planet.getId().equals(a[0])?planet:remote.getId().equals(a[0])?remote:null;
                default -> null;
            });
        }
    }
    private static <T> T proxy(Class<T> type,BiFunction<String,Object[],Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)-> {
            if(m.getDeclaringClass()==Object.class) return switch(m.getName()) {
                case "equals" -> p==a[0]; case "hashCode" -> System.identityHashCode(p); default -> type.getSimpleName();
            };
            Object result=handler.apply(m.getName(),a);
            if(result!=null || !m.getReturnType().isPrimitive() || m.getReturnType()==void.class) return result;
            if(m.getReturnType()==boolean.class) return false;
            if(m.getReturnType()==float.class) return 0f;
            if(m.getReturnType()==double.class) return 0d;
            if(m.getReturnType()==long.class) return 0L;
            return 0;
        }));
    }
    private static void fails(Runnable operation) {
        try {operation.run();throw new AssertionError("Expected failure");} catch(IllegalArgumentException | IllegalStateException expected) {}
    }
    private static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
