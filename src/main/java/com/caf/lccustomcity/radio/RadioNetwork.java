package com.caf.lccustomcity.radio;

import com.caf.lccustomcity.client.radio.RadioClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class RadioNetwork {
    private RadioNetwork() {}
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("caf-radio-1");
        registrar.playToServer(Request.TYPE,Request.CODEC,(message,context) -> context.enqueueWork(() -> {
            if(context.player() instanceof ServerPlayer player) RadioService.request(player,message.action(),message.id());
        }));
        registrar.playToClient(Snapshot.TYPE,Snapshot.CODEC,(message,context) -> context.enqueueWork(() -> {
            if(FMLEnvironment.dist == Dist.CLIENT) {
                RadioClient.accept(message.json());
                if(message.open()) RadioClient.openRadioScreen();
            }
        }));
    }
    public static void request(String action,String id) { PacketDistributor.sendToServer(new Request(action,id)); }
    public static void send(ServerPlayer player,String json,boolean open) { PacketDistributor.sendToPlayer(player,new Snapshot(json,open)); }
    public record Request(String action,String id) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("caf_lc_customcity","radio_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC = StreamCodec.of(
                (buffer,message)->{buffer.writeUtf(message.action,16);buffer.writeUtf(message.id,160);},
                buffer->new Request(buffer.readUtf(16),buffer.readUtf(160)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Snapshot(String json,boolean open) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("caf_lc_customcity","radio_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC = StreamCodec.of(
                (buffer,message)->{buffer.writeUtf(message.json,196608);buffer.writeBoolean(message.open);},
                buffer->new Snapshot(buffer.readUtf(196608),buffer.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
