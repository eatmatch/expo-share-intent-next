import React from "react";
import { useShareIntentContext } from "expo-share-intent-next";
import { Text, View, StyleSheet, Button } from "react-native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { RootStackParamList } from "./types";

interface Props {
  navigation: NativeStackNavigationProp<RootStackParamList, "Home">;
}

export default function HomeScreen({ navigation }: Props) {
  const { shareIntent } = useShareIntentContext();
  console.log("HomeScreen", shareIntent);

  return (
    <View style={styles.container}>
      <Text style={styles.heading}>Welcome to Expo Share Intent Example !</Text>
      <Text>Try to share a content to access specific page</Text>
      <View style={styles.buttonContainer}>
        <Button
          title="Direct Share Demo"
          onPress={() => navigation.navigate("Contacts")}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#fff",
    alignItems: "center",
    justifyContent: "center",
    padding: 20,
  },
  heading: {
    fontSize: 20,
    fontWeight: "bold",
    textAlign: "center",
    marginBottom: 10,
  },
  buttonContainer: {
    marginTop: 30,
    width: "80%",
  },
});
