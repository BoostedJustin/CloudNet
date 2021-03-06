/*
 * Copyright (c) Tarek Hosni El Alaoui 2017
 */

package de.dytanic.cloudnet.bridge.internal.serverselectors;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import de.dytanic.cloudnet.api.CloudAPI;
import de.dytanic.cloudnet.api.handlers.adapter.NetworkHandlerAdapter;
import de.dytanic.cloudnet.bridge.CloudServer;
import de.dytanic.cloudnet.bridge.event.bukkit.BukkitMobInitEvent;
import de.dytanic.cloudnet.bridge.event.bukkit.BukkitMobUpdateEvent;
import de.dytanic.cloudnet.bridge.internal.util.ItemStackBuilder;
import de.dytanic.cloudnet.bridge.internal.util.ReflectionUtil;
import de.dytanic.cloudnet.lib.NetworkUtils;
import de.dytanic.cloudnet.lib.server.ServerState;
import de.dytanic.cloudnet.lib.server.info.ServerInfo;
import de.dytanic.cloudnet.lib.serverselectors.mob.MobPosition;
import de.dytanic.cloudnet.lib.serverselectors.mob.MobItemLayout;
import de.dytanic.cloudnet.lib.serverselectors.mob.ServerMob;
import de.dytanic.cloudnet.lib.serverselectors.mob.MobConfig;
import de.dytanic.cloudnet.lib.utility.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Tareko on 25.08.2017.
 */
@Getter
public final class MobSelector {

    @Getter
    private static MobSelector instance;

    @Setter
    private Map<UUID, MobImpl> mobs;

    @Setter
    private MobConfig mobConfig;

    @Getter
    private Map<String, ServerInfo> servers = new ConcurrentHashMap<>();

    public MobSelector(MobConfig mobConfig)
    {
        instance = this;
        this.mobConfig = mobConfig;
    }

    public void init()
    {
        CloudAPI.getInstance().getNetworkHandlerProvider().registerHandler(new NetworkHandlerAdapterImplx());

        Bukkit.getScheduler().runTask(CloudServer.getInstance().getPlugin(), new Runnable() {
            @Override
            public void run()
            {
                NetworkUtils.addAll(servers, MapWrapper.collectionCatcherHashMap(CloudAPI.getInstance().getServers(), new Catcher<String, ServerInfo>() {
                    @Override
                    public String doCatch(ServerInfo key)
                    {
                        return key.getServiceId().getServerId();
                    }
                }));
                Bukkit.getScheduler().runTaskAsynchronously(CloudServer.getInstance().getPlugin(), new Runnable() {
                    @Override
                    public void run()
                    {
                        for (ServerInfo serverInfo : servers.values())
                        {
                            handleUpdate(serverInfo);
                        }
                    }
                });
            }
        });

        if (ReflectionUtil.forName("org.bukkit.entity.ArmorStand") != null)
        {
            try
            {
                Bukkit.getPluginManager().registerEvents((Listener) ReflectionUtil.forName("de.dytanic.cloudnet.bridge.internal.listener.v18_112.ArmorStandListener").newInstance(), CloudServer.getInstance().getPlugin());
            } catch (InstantiationException | IllegalAccessException e)
            {
                e.printStackTrace();
            }
        }

        Bukkit.getPluginManager().registerEvents(new ListenrImpl(), CloudServer.getInstance().getPlugin());
    }

    @Deprecated
    public void shutdown()
    {
        for (MobImpl mobImpl : this.mobs.values())
        {
            if (mobImpl.displayMessage != null)
            {
                try
                {
                    Entity entity = (Entity) mobImpl.displayMessage;
                    if(entity.getPassenger() != null)
                    {
                        entity.getPassenger().remove();
                    }
                    mobImpl.displayMessage.getClass().getMethod("remove").invoke(mobImpl.displayMessage);
                } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e)
                {
                }
            }
            mobImpl.entity.remove();
        }
        mobs.clear();
    }

    public Location toLocation(MobPosition position)
    {
        return new Location(Bukkit.getWorld(position.getWorld()), position.getX(), position.getY(), position.getZ(), position.getYaw(), position.getPitch());
    }

    public MobPosition toPosition(String group, Location location)
    {
        return new MobPosition(group, location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    public Inventory create(MobConfig mobConfig, ServerMob mob)
    {
        Inventory inventory = Bukkit.createInventory(null, mobConfig.getInventorySize(), ChatColor.translateAlternateColorCodes('&', mob.getDisplay() + " "));

        for (Map.Entry<Integer, MobItemLayout> mobItem : mobConfig.getDefaultItemInventory().entrySet())
            inventory.setItem(mobItem.getKey() - 1, transform(mobItem.getValue()));
        return inventory;
    }

    private ItemStack transform(MobItemLayout mobItemLayout)
    {
        return ItemStackBuilder.builder(mobItemLayout.getItemId(), 1, mobItemLayout.getSubId())
                .lore(new ArrayList<>(CollectionWrapper.transform(mobItemLayout.getLore(), new Catcher<String, String>() {
                    @Override
                    public String doCatch(String key)
                    {
                        return ChatColor.translateAlternateColorCodes('&', key);
                    }
                }))).displayName(ChatColor.translateAlternateColorCodes('&', mobItemLayout.getDisplay())).build();
    }

    private ItemStack transform(MobItemLayout mobItemLayout, ServerInfo serverInfo)
    {
        return ItemStackBuilder.builder(mobItemLayout.getItemId(), 1, mobItemLayout.getSubId())
                .lore(new ArrayList<>(CollectionWrapper.transform(mobItemLayout.getLore(), new Catcher<String, String>() {
                    @Override
                    public String doCatch(String key)
                    {
                        return initPatterns(ChatColor.translateAlternateColorCodes('&', key), serverInfo);
                    }
                }))).displayName(initPatterns(ChatColor.translateAlternateColorCodes('&', mobItemLayout.getDisplay()), serverInfo)).build();
    }

    private String initPatterns(String x, ServerInfo serverInfo)
    {
        return x.replace("%server%", serverInfo.getServiceId().getServerId())
                .replace("%id%", serverInfo.getServiceId().getId() + "")
                .replace("%host%", serverInfo.getHost())
                .replace("%port%", serverInfo.getPort() + "")
                .replace("%memory%", serverInfo.getMemory() + "MB")
                .replace("%online_players%", serverInfo.getOnlineCount() + "")
                .replace("%max_players%", serverInfo.getMaxPlayers() + "")
                .replace("%motd%", ChatColor.translateAlternateColorCodes('&', serverInfo.getMotd()))
                .replace("%state%", serverInfo.getServerState().name() + "")
                .replace("%wrapper%", serverInfo.getServiceId().getWrapperId() + "")
                .replace("%extra%", serverInfo.getServerConfig().getExtra())
                .replace("%template%", serverInfo.getTemplate().getName())
                .replace("%group%", serverInfo.getServiceId().getGroup());
    }

    public Return<Integer, Integer> getOnlineCount(String group)
    {
        int atomicInteger = 0;
        int atomicInteger1 = 0;
        for (ServerInfo serverInfo : this.servers.values())
        {
            if (serverInfo.getServiceId().getGroup().equalsIgnoreCase(group))
            {
                atomicInteger = atomicInteger + serverInfo.getOnlineCount();
                atomicInteger1 = atomicInteger1 + serverInfo.getMaxPlayers();
            }
        }
        return new Return<>(atomicInteger, atomicInteger1);
    }

    private List<ServerInfo> getServers(String group)
    {
        List<ServerInfo> groups = new ArrayList<>(CollectionWrapper.filterMany(this.servers.values(), new Acceptable<ServerInfo>() {
            @Override
            public boolean isAccepted(ServerInfo serverInfo)
            {
                return serverInfo.getServiceId().getGroup() != null && serverInfo.getServiceId().getGroup().equalsIgnoreCase(group);
            }
        }));

        return groups;
    }

    @Deprecated
    public void unstableEntity(Entity entity)
    {
        try
        {
            Class<?> nbt = ReflectionUtil.reflectNMSClazz(".NBTTagCompound");
            Class<?> entityClazz = ReflectionUtil.reflectNMSClazz(".Entity");
            Object object = nbt.newInstance();

            Object nmsEntity = entity.getClass().getMethod("getHandle", new Class[]{}).invoke(entity);
            try
            {
                entityClazz.getMethod("e", nbt).invoke(nmsEntity, object);
            } catch (Exception ex)
            {
                entityClazz.getMethod("save", nbt).invoke(nmsEntity, object);
            }
            object.getClass().getMethod("setInt", String.class, int.class).invoke(object, "NoAI", 1);
            object.getClass().getMethod("setInt", String.class, int.class).invoke(object, "Silent", 1);
            entityClazz.getMethod("f", nbt).invoke(nmsEntity, object);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e)
        {
            e.printStackTrace();
            System.out.println("[CLOUD] Disabling NoAI and Silent support for " + entity.getEntityId());
            ((LivingEntity) entity).addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 100));
        }
    }

    private Collection<ServerInfo> filter(String group)
    {
        return CollectionWrapper.filterMany(servers.values(), new Acceptable<ServerInfo>() {
            @Override
            public boolean isAccepted(ServerInfo value)
            {
                return value.getServiceId().getGroup().equals(group);
            }
        });
    }

    public void handleUpdate(ServerInfo serverInfo)
    {
        for (MobImpl mob : this.mobs.values())
        {
            if (mob.getMob().getTargetGroup().equals(serverInfo.getServiceId().getGroup()))
            {
                updateCustom(mob.getMob(), mob.getDisplayMessage());
                Bukkit.getPluginManager().callEvent(new BukkitMobUpdateEvent(mob.getMob()));
                mob.getServerPosition().clear();
                filter(serverInfo.getServiceId().getGroup());
                Collection<ServerInfo> serverInfos = filter(serverInfo.getServiceId().getGroup());
                final AtomicInteger index = new AtomicInteger(0);
                for (ServerInfo server : serverInfos)
                {
                    if (server.isOnline() && server.getServerState().equals(ServerState.LOBBY) && !server.getServerConfig().isHideServer())
                    {
                        while (mobConfig.getDefaultItemInventory().containsKey((index.get() + 1)))
                        {
                            index.addAndGet(1);
                        }
                        if ((mobConfig.getInventorySize() - 1) <= index.get()) break;

                        final int value = index.get();
                        Bukkit.getScheduler().runTask(CloudServer.getInstance().getPlugin(), new Runnable() {
                            @Override
                            public void run()
                            {
                                mob.getInventory().setItem(value, transform(mobConfig.getItemLayout(), server));
                                mob.getServerPosition().put(value, server.getServiceId().getServerId());
                            }
                        });
                        index.addAndGet(1);
                    }
                }

                while (index.get() < (mob.getInventory().getSize()))
                {
                    if (!mobConfig.getDefaultItemInventory().containsKey(index.get() + 1))
                        mob.getInventory().setItem(index.get(), new ItemStack(Material.AIR));
                    index.addAndGet(1);
                }
            }
        }
    }

    public void updateCustom(ServerMob serverMob, Object armorStand)
    {
        Return<Integer, Integer> x = getOnlineCount(serverMob.getTargetGroup());
        if (armorStand != null)
        {
            try
            {

                armorStand.getClass().getMethod("setCustomName", String.class).invoke(armorStand, ChatColor.translateAlternateColorCodes('&', serverMob.getDisplayMessage() + "")
                        .replace("%max_players%", x.getSecond() + "").replace("%group%", serverMob.getTargetGroup()).replace("%group_online%", x
                                .getFirst() + ""));
            } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e)
            {
            }
        }
    }

    //MobImpl
    @Getter
    @Setter
    @AllArgsConstructor
    public static class MobImpl {
        private UUID uniqueId;

        private ServerMob mob;

        private Entity entity;

        private Inventory inventory;

        private Map<Integer, String> serverPosition;

        private Object displayMessage;
    }

    private class NetworkHandlerAdapterImplx extends NetworkHandlerAdapter {

        @Override
        public void onServerRemove(ServerInfo serverInfo)
        {
            servers.remove(serverInfo.getServiceId().getServerId());
            handleUpdate(serverInfo);
        }

        @Override
        public void onServerAdd(ServerInfo serverInfo)
        {
            servers.put(serverInfo.getServiceId().getServerId(), serverInfo);
            handleUpdate(serverInfo);
        }

        @Override
        public void onServerInfoUpdate(ServerInfo serverInfo)
        {
            servers.put(serverInfo.getServiceId().getServerId(), serverInfo);
            handleUpdate(serverInfo);
        }
    }

    public Collection<Inventory> inventories()
    {
        return CollectionWrapper.getCollection(this.mobs, new Catcher<Inventory, MobImpl>() {
            @Override
            public Inventory doCatch(MobImpl key)
            {
                return key.getInventory();
            }
        });
    }

    public MobImpl find(Inventory inventory)
    {
        return CollectionWrapper.filter(this.mobs.values(), new Acceptable<MobImpl>() {
            @Override
            public boolean isAccepted(MobImpl value)
            {
                return value.getInventory().equals(inventory);
            }
        });
    }

    private class ListenrImpl implements Listener {

        @EventHandler
        public void handleRightClick(PlayerInteractEntityEvent e)
        {
            MobImpl mobImpl = CollectionWrapper.filter(mobs.values(), new Acceptable<MobImpl>() {
                @Override
                public boolean isAccepted(MobImpl value)
                {
                    return value.getEntity().getEntityId() == e.getRightClicked().getEntityId();
                }
            });

            if (mobImpl != null)
            {
                e.setCancelled(true);
                if (!CloudAPI.getInstance().getServerGroupData(mobImpl.getMob().getTargetGroup()).isMaintenance())
                {
                    if(mobImpl.getMob().getAutoJoin() != null && mobImpl.getMob().getAutoJoin())
                    {
                        ByteArrayDataOutput byteArrayDataOutput = ByteStreams.newDataOutput();
                        byteArrayDataOutput.writeUTF("Connect");

                        List<ServerInfo> serverInfos = getServers(mobImpl.getMob().getTargetGroup());

                        for (ServerInfo serverInfo : serverInfos)
                            if (serverInfo.getOnlineCount() < serverInfo.getMaxPlayers() && serverInfo.getServerState().equals(ServerState.LOBBY))
                            {
                                byteArrayDataOutput.writeUTF(serverInfo.getServiceId().getServerId());
                                e.getPlayer().sendPluginMessage(CloudServer.getInstance().getPlugin(), "BungeeCord", byteArrayDataOutput.toByteArray());
                                return;
                            }
                    }
                    else e.getPlayer().openInventory(mobImpl.getInventory());
                } else e.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', CloudAPI.getInstance().getCloudNetwork().getMessages().getString("mob-selector-maintenance-message")));
            }
        }

        @EventHandler
        public void entityDamage(EntityDamageEvent e)
        {
            MobImpl mob = CollectionWrapper.filter(mobs.values(), new Acceptable<MobImpl>() {
                @Override
                public boolean isAccepted(MobImpl value)
                {
                    return e.getEntity().getEntityId() == value.getEntity().getEntityId();
                }
            });
            if (mob != null)
            {
                e.setCancelled(true);
            }
        }

        @EventHandler
        public void handleInventoryClick(InventoryClickEvent e)
        {
            if (!(e.getWhoClicked() instanceof Player)) return;

            if (inventories().contains(e.getInventory()) && e.getCurrentItem() != null && e.getSlot() == e.getRawSlot())
            {
                e.setCancelled(true);
                if (mobConfig.getItemLayout().getItemId() == e.getCurrentItem().getTypeId())
                {
                    MobImpl mob = find(e.getInventory());
                    if (mob.getServerPosition().containsKey(e.getSlot()))
                    {
                        if (CloudAPI.getInstance().getServerId().equalsIgnoreCase(mob.getServerPosition().get(e.getSlot()))) return;
                        ByteArrayDataOutput byteArrayDataOutput = ByteStreams.newDataOutput();
                        byteArrayDataOutput.writeUTF("Connect");
                        byteArrayDataOutput.writeUTF(mob.getServerPosition().get(e.getSlot()));
                        ((Player) e.getWhoClicked()).sendPluginMessage(CloudServer.getInstance().getPlugin(), "BungeeCord", byteArrayDataOutput.toByteArray());
                    }
                }
            }
        }

        @EventHandler
        public void onSave(WorldSaveEvent e)
        {
            Map<UUID, ServerMob> filteredMobs = MapWrapper.transform(MobSelector.this.mobs, new Catcher<UUID, UUID>() {
                @Override
                public UUID doCatch(UUID key)
                {
                    return key;
                }
            }, new Catcher<ServerMob, MobImpl>() {
                @Override
                public ServerMob doCatch(MobImpl key)
                {
                    return key.getMob();
                }
            });

            MobSelector.getInstance().shutdown();


            Bukkit.getScheduler().runTaskLater(CloudServer.getInstance().getPlugin(), new Runnable() {
                @Override
                public void run()
                {
                    MobSelector.getInstance().setMobs(MapWrapper.transform(filteredMobs, new Catcher<UUID, UUID>() {
                        @Override
                        public UUID doCatch(UUID key)
                        {
                            return key;
                        }
                    }, new Catcher<MobImpl, ServerMob>() {
                        @Override
                        public MobImpl doCatch(ServerMob key)
                        {
                            MobSelector.getInstance().toLocation(key.getPosition()).getChunk().load();
                            Entity entity = MobSelector.getInstance().toLocation(key.getPosition()).getWorld().spawnEntity(MobSelector.getInstance().toLocation(key.getPosition()), EntityType.valueOf(key.getType()));
                            Object armorStand = ReflectionUtil.armorstandCreation(MobSelector.getInstance().toLocation(key.getPosition()), entity, key);

                            if(armorStand != null)
                            {
                                MobSelector.getInstance().updateCustom(key, armorStand);
                                Entity armor = (Entity) armorStand;
                                if(armor.getPassenger() == null && key.getItemId() != null)
                                {
                                    Item item = Bukkit.getWorld(key.getPosition().getWorld()).dropItem(armor.getLocation(), new ItemStack(key.getItemId()));
                                    item.setPickupDelay(Integer.MAX_VALUE);
                                    item.setTicksLived(Integer.MAX_VALUE);
                                    armor.setPassenger(item);
                                }
                            }

                            if(entity instanceof Villager)
                            {
                                ((Villager) entity).setProfession(Villager.Profession.FARMER);
                            }

                            MobSelector.getInstance().unstableEntity(entity);
                            entity.setCustomNameVisible(true);
                            entity.setCustomName(ChatColor.translateAlternateColorCodes('&', key.getDisplay()));
                            MobImpl mob =  new MobImpl(key.getUniqueId(), key, entity, MobSelector.getInstance().create(mobConfig, key), new HashMap<>(), armorStand);
                            Bukkit.getPluginManager().callEvent(new BukkitMobInitEvent(mob));
                            return mob;
                        }
                    }));
                    Bukkit.getScheduler().runTaskAsynchronously(CloudServer.getInstance().getPlugin(), new Runnable() {
                        @Override
                        public void run()
                        {
                            for (ServerInfo serverInfo : getServers().values())
                            {
                                MobSelector.getInstance().handleUpdate(serverInfo);
                            }
                        }
                    });
                }
            }, 40);
        }
    }
}